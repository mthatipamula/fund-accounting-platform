package com.fundaccounting.accounting;

import com.fundaccounting.accounting.AccountingRepository.PeriodClose;
import com.fundaccounting.engine.FundEngine;
import com.fundaccounting.engine.FundState;
import com.fundaccounting.engine.InMemoryMarketData;
import com.fundaccounting.engine.model.CorporateAction;
import com.fundaccounting.engine.model.DayCount;
import com.fundaccounting.engine.model.Fund;
import com.fundaccounting.engine.model.Security;
import com.fundaccounting.grpc.marketdata.v1.FxHistoryRequest;
import com.fundaccounting.grpc.marketdata.v1.GetSecuritiesRequest;
import com.fundaccounting.grpc.marketdata.v1.HistoryRequest;
import com.fundaccounting.grpc.marketdata.v1.MarketDataServiceGrpc.MarketDataServiceBlockingStub;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Assembles everything the engine needs, as known at a moment, and runs it.
 *
 * <p>Independent loads (trades and capital from the database; securities and corporate actions over gRPC) run in
 * parallel on virtual threads, then prices and FX for the securities found. Tracing context is carried onto those
 * threads so one request shows as one trace across services.
 *
 * <p>Closed periods: when a report's as-of date falls inside a closed period and no known-at is given, the books
 * are shown exactly as they were when the period was closed, so later corrections can't change reported numbers.
 */
@Service
public class ValuationService {
    private static final int GRPC_DEADLINE_SECONDS = 15;
    private final AccountingRepository repo;
    private final MarketDataServiceBlockingStub marketData;
    private final Clock clock;

    public ValuationService(AccountingRepository repo, MarketDataServiceBlockingStub marketData, Clock clock) {
        this.repo = repo;
        this.marketData = marketData;
        this.clock = clock;
    }

    public record Valuation(FundState state, Fund fund, Instant knownAt, boolean lockedByClose, LocalDate closedThrough,
                            Map<String, Security> securities) {}

    public Valuation value(String fundId, LocalDate asOf, Instant requestedKnownAt) {
        Fund fund = repo.fund(fundId);
        if (asOf.isBefore(fund.inceptionDate())) throw new IllegalArgumentException("asOf is before the fund's inception " + fund.inceptionDate());
        PeriodClose close = repo.currentClose(fundId).orElse(null);
        boolean locked = requestedKnownAt == null && close != null && !asOf.isAfter(close.closedThrough());
        Instant knownAt = locked ? close.performedAt() : requestedKnownAt != null ? requestedKnownAt : clock.instant();
        String k = knownAt.toString();
        MarketDataServiceBlockingStub marketData = withCallerToken(this.marketData);

        try (ExecutorService pool = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(),
                ContextSnapshotFactory.builder().build()::captureAll)) {
            Future<List<com.fundaccounting.engine.model.Trade>> trades = pool.submit(() -> repo.tradesAsKnownAt(fundId, knownAt));
            Future<List<com.fundaccounting.engine.model.CapitalActivity>> capital = pool.submit(() ->
                    repo.capitalAsKnownAt(fundId, knownAt).stream().map(AccountingRepository.CapitalRow::toEngine).toList());
            Future<Map<String, Security>> securities = pool.submit(() -> grpc("securities", () -> securities(marketData)));
            Future<List<CorporateAction>> actions = pool.submit(() -> grpc("corporate actions",
                    () -> corporateActions(marketData, fund, asOf, k)));

            Map<String, Security> secs = securities.get();
            List<String> currencies = secs.values().stream().map(Security::currency).distinct()
                    .filter(c -> !c.equals(fund.baseCurrency())).toList();
            InMemoryMarketData md = new InMemoryMarketData();
            Future<?> prices = pool.submit(() -> grpc("prices", () -> {
                var resp = marketData.withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS).getPriceHistory(HistoryRequest.newBuilder()
                        .setFromDate(fund.inceptionDate().minusDays(10).toString()).setToDate(asOf.toString()).setKnownAt(k).build());
                synchronized (md) { resp.getPointsList().forEach(p -> md.price(p.getSecurityId(), LocalDate.parse(p.getDate()), new BigDecimal(p.getPrice()))); }
                return null;
            }));
            Future<?> fx = pool.submit(() -> grpc("FX rates", () -> {
                if (currencies.isEmpty()) return null;
                var resp = marketData.withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS).getFxHistory(FxHistoryRequest.newBuilder()
                        .addAllCurrencies(currencies).setBaseCurrency(fund.baseCurrency())
                        .setFromDate(fund.inceptionDate().minusDays(10).toString()).setToDate(asOf.toString()).setKnownAt(k).build());
                synchronized (md) { resp.getPointsList().forEach(p -> md.fx(p.getCurrency(), LocalDate.parse(p.getDate()), new BigDecimal(p.getRate()))); }
                return null;
            }));
            prices.get();
            fx.get();
            FundState state = FundEngine.run(fund, secs, trades.get(), actions.get(), capital.get(), md, asOf);
            return new Valuation(state, fund, knownAt, locked, close == null ? null : close.closedThrough(), secs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Valuation interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        }
    }

    private static Map<String, Security> securities(MarketDataServiceBlockingStub marketData) {
        Map<String, Security> map = new LinkedHashMap<>();
        for (var s : marketData.withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS).getSecurities(GetSecuritiesRequest.getDefaultInstance()).getSecuritiesList()) {
            Security sec = "BOND".equals(s.getAssetClass())
                    ? Security.bond(s.getId(), s.getSymbol(), s.getName(), s.getCurrency(), new BigDecimal(s.getCouponRate()),
                    s.getCouponsPerYear(), LocalDate.parse(s.getIssueDate()), LocalDate.parse(s.getMaturityDate()), DayCount.valueOf(s.getDayCount()))
                    : Security.equity(s.getId(), s.getSymbol(), s.getName(), s.getCurrency());
            map.put(sec.id(), sec);
        }
        return map;
    }

    private static List<CorporateAction> corporateActions(MarketDataServiceBlockingStub marketData, Fund fund, LocalDate asOf, String knownAt) {
        var resp = marketData.withDeadlineAfter(GRPC_DEADLINE_SECONDS, TimeUnit.SECONDS).getCorporateActions(HistoryRequest.newBuilder()
                .setFromDate(fund.inceptionDate().toString()).setToDate(asOf.toString()).setKnownAt(knownAt).build());
        return resp.getActionsList().stream().map(a -> new CorporateAction(a.getId(), a.getVersion(), a.getSecurityId(),
                CorporateAction.Type.valueOf(a.getType()), LocalDate.parse(a.getExDate()), LocalDate.parse(a.getPayDate()),
                a.getAmountPerShare().isEmpty() ? null : new BigDecimal(a.getAmountPerShare()),
                a.getRatio().isEmpty() ? null : new BigDecimal(a.getRatio()), Instant.parse(a.getRecordedAt()), null, false)).toList();
    }

    /**
     * Zero trust between services: reference data checks the signed-in user's token on gRPC calls too, so the
     * caller's bearer token is forwarded rather than trusting the network.
     */
    private static MarketDataServiceBlockingStub withCallerToken(MarketDataServiceBlockingStub stub) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) return stub;
        String bearer = "Bearer " + jwt.getToken().getTokenValue();
        return stub.withCallCredentials(new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo info, Executor executor, MetadataApplier applier) {
                Metadata headers = new Metadata();
                headers.put(AUTHORIZATION, bearer);
                applier.apply(headers);
            }
        });
    }

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** gRPC failures become a clear 503 for the caller; one retry for a transient UNAVAILABLE. */
    private static <T> T grpc(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                try { return call.get(); } catch (StatusRuntimeException retry) { e = retry; }
            }
            throw new MarketDataUnavailable("Could not load " + what + " from reference data (" + e.getStatus().getCode() + ")", e);
        }
    }

    public static class MarketDataUnavailable extends RuntimeException {
        public MarketDataUnavailable(String message, Throwable cause) { super(message, cause); }
    }
}
