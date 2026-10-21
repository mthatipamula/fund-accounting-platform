package com.fundaccounting.refdata;

import com.fundaccounting.common.AsOf;
import com.fundaccounting.grpc.marketdata.v1.CorporateAction;
import com.fundaccounting.grpc.marketdata.v1.CorporateActionsResponse;
import com.fundaccounting.grpc.marketdata.v1.FxHistoryRequest;
import com.fundaccounting.grpc.marketdata.v1.FxHistoryResponse;
import com.fundaccounting.grpc.marketdata.v1.FxPoint;
import com.fundaccounting.grpc.marketdata.v1.GetSecuritiesRequest;
import com.fundaccounting.grpc.marketdata.v1.GetSecuritiesResponse;
import com.fundaccounting.grpc.marketdata.v1.HistoryRequest;
import com.fundaccounting.grpc.marketdata.v1.MarketDataServiceGrpc;
import com.fundaccounting.grpc.marketdata.v1.PriceHistoryResponse;
import com.fundaccounting.grpc.marketdata.v1.PricePoint;
import com.fundaccounting.grpc.marketdata.v1.Security;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * gRPC for service-to-service calls: the accounting service loads securities, prices, FX and corporate actions as
 * known at a moment in a few calls instead of thousands of REST requests. Internal only (not exposed by the gateway).
 */
@Service
public class MarketDataGrpcService extends MarketDataServiceGrpc.MarketDataServiceImplBase {
    private final ReferenceDataRepository repo;

    public MarketDataGrpcService(ReferenceDataRepository repo) { this.repo = repo; }

    @Override
    public void getSecurities(GetSecuritiesRequest req, StreamObserver<GetSecuritiesResponse> out) {
        respond(out, () -> {
            var b = GetSecuritiesResponse.newBuilder();
            for (var s : repo.securities(req.getSecurityIdsList())) {
                b.addSecurities(Security.newBuilder().setId(s.id()).setSymbol(s.symbol()).setName(s.name())
                        .setAssetClass(s.assetClass()).setCurrency(s.currency()).setCouponRate(str(s.couponRate()))
                        .setCouponsPerYear(s.couponsPerYear() == null ? 0 : s.couponsPerYear())
                        .setIssueDate(str(s.issueDate())).setMaturityDate(str(s.maturityDate()))
                        .setDayCount(Objects.toString(s.dayCount(), "")));
            }
            return b.build();
        });
    }

    @Override
    public void getPriceHistory(HistoryRequest req, StreamObserver<PriceHistoryResponse> out) {
        respond(out, () -> {
            var b = PriceHistoryResponse.newBuilder();
            for (var p : repo.prices(req.getSecurityIdsList(), LocalDate.parse(req.getFromDate()), LocalDate.parse(req.getToDate()), knownAt(req.getKnownAt()))) {
                b.addPoints(PricePoint.newBuilder().setSecurityId(p.securityId()).setDate(p.date().toString()).setPrice(p.price().toPlainString()));
            }
            return b.build();
        });
    }

    @Override
    public void getFxHistory(FxHistoryRequest req, StreamObserver<FxHistoryResponse> out) {
        respond(out, () -> {
            var b = FxHistoryResponse.newBuilder();
            for (var r : repo.fx(req.getCurrenciesList(), req.getBaseCurrency(), LocalDate.parse(req.getFromDate()),
                    LocalDate.parse(req.getToDate()), knownAt(req.getKnownAt()))) {
                b.addPoints(FxPoint.newBuilder().setCurrency(r.currency()).setDate(r.date().toString()).setRate(r.rate().toPlainString()));
            }
            return b.build();
        });
    }

    @Override
    public void getCorporateActions(HistoryRequest req, StreamObserver<CorporateActionsResponse> out) {
        respond(out, () -> {
            Instant k = knownAt(req.getKnownAt());
            var b = CorporateActionsResponse.newBuilder();
            for (var a : repo.corporateActions(req.getSecurityIdsList())) {
                boolean current = !a.recordedAt().isAfter(k) && (a.supersededAt() == null || a.supersededAt().isAfter(k)) && !a.cancelled();
                if (!current) continue;
                b.addActions(CorporateAction.newBuilder().setId(a.id()).setVersion(a.version()).setSecurityId(a.securityId())
                        .setType(a.type()).setExDate(a.exDate().toString()).setPayDate(a.payDate().toString())
                        .setAmountPerShare(str(a.amountPerShare())).setRatio(str(a.ratio())).setRecordedAt(a.recordedAt().toString()));
            }
            return b.build();
        });
    }

    private static Instant knownAt(String v) { return AsOf.knownAt(v); }

    private static String str(Object v) {
        return v == null ? "" : v instanceof BigDecimal d ? d.toPlainString() : v.toString();
    }

    /** Bad input becomes INVALID_ARGUMENT; anything else INTERNAL without leaking details. */
    private static <T> void respond(StreamObserver<T> out, Supplier<T> work) {
        try {
            out.onNext(work.get());
            out.onCompleted();
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(MarketDataGrpcService.class).error("gRPC call failed", e);
            out.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        }
    }
}
