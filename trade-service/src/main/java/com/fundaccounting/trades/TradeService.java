package com.fundaccounting.trades;

import tools.jackson.databind.ObjectMapper;
import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.trades.TradeDto.Request;
import com.fundaccounting.trades.TradeDto.Version;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Trade capture. Every change is a new version written together with its outbox event in one transaction. */
@Service
public class TradeService {
    public static final String TOPIC = "trade-events";
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ObjectMapper json;

    public TradeService(JdbcClient jdbc, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public Version create(Request r, String user, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<String> existing = jdbc.sql("SELECT trade_id FROM idempotency_keys WHERE key = :k").param("k", idempotencyKey)
                    .query(String.class).optional();
            if (existing.isPresent()) return current(existing.get());
        }
        checkOpen(r.fundId(), r.tradeDate());
        String id = "T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Version v = insert(id, 1, r, user, r.reason(), false, clock.instant());
        if (idempotencyKey != null) {
            jdbc.sql("INSERT INTO idempotency_keys (key, trade_id) VALUES (:k, :id)").param("k", idempotencyKey).param("id", id).update();
        }
        return v;
    }

    /** Books a batch in one transaction: either every trade is booked or none is. */
    @Transactional
    public List<String> createAll(List<Request> rows, String user) {
        return rows.stream().map(r -> create(r, user, null).id()).toList();
    }

    @Transactional
    public Version amend(String id, int expectedVersion, Request r, String user, String reason) {
        Version cur = lockCurrent(id, expectedVersion);
        if (!cur.fundId().equals(r.fundId())) throw ApiException.badRequest("A trade can't move to another fund; cancel and rebook it");
        checkOpen(cur.fundId(), cur.tradeDate());
        checkOpen(r.fundId(), r.tradeDate());
        Instant now = clock.instant();
        supersede(id, cur.version(), now);
        return insert(id, cur.version() + 1, r, user, reason, false, now);
    }

    @Transactional
    public Version cancel(String id, int expectedVersion, String user, String reason) {
        Version cur = lockCurrent(id, expectedVersion);
        checkOpen(cur.fundId(), cur.tradeDate());
        Instant now = clock.instant();
        supersede(id, cur.version(), now);
        Request same = new Request(cur.fundId(), cur.securityId(), cur.side(), cur.quantity(), cur.price(), cur.commission(),
                cur.tradeDate(), cur.settleDate(), reason);
        return insert(id, cur.version() + 1, same, user, reason, true, now);
    }

    /** Trades as known at a moment: one version per trade; cancelled ones are included and flagged. */
    public List<Version> asKnownAt(String fundId, Instant knownAt) {
        return jdbc.sql(SELECT + " WHERE fund_id = :f AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k) ORDER BY trade_date DESC, id")
                .param("f", fundId).param("k", Timestamp.from(knownAt)).query(TradeService::row).list();
    }

    public List<Version> history(String id) {
        List<Version> versions = jdbc.sql(SELECT + " WHERE id = :id ORDER BY version").param("id", id).query(TradeService::row).list();
        if (versions.isEmpty()) throw ApiException.notFound("Trade " + id);
        return versions;
    }

    public Version current(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND superseded_at IS NULL").param("id", id).query(TradeService::row)
                .optional().orElseThrow(() -> ApiException.notFound("Trade " + id));
    }

    @Transactional
    public void periodClosed(String fundId, LocalDate closedThrough) {
        jdbc.sql("""
                INSERT INTO closed_periods (fund_id, closed_through, updated_at) VALUES (:f, :d, now())
                ON CONFLICT (fund_id) DO UPDATE SET closed_through = :d, updated_at = now()""")
                .param("f", fundId).param("d", closedThrough).update();
    }

    // ---------------------------------------------------------------- internals

    private static final String SELECT = """
            SELECT id, version, fund_id, security_id, side, quantity, price, commission, trade_date, settle_date,
                   recorded_at, superseded_at, cancelled, entered_by, reason FROM trade_versions""";

    private void checkOpen(String fundId, LocalDate tradeDate) {
        jdbc.sql("SELECT closed_through FROM closed_periods WHERE fund_id = :f").param("f", fundId).query(LocalDate.class).optional()
                .filter(closed -> !tradeDate.isAfter(closed))
                .ifPresent(closed -> {
                    throw ApiException.conflict("The books are closed through " + closed + ". Book the correction with a later trade date.");
                });
    }

    private Version lockCurrent(String id, int expectedVersion) {
        Version cur = jdbc.sql(SELECT + " WHERE id = :id AND superseded_at IS NULL FOR UPDATE").param("id", id)
                .query(TradeService::row).optional().orElseThrow(() -> ApiException.notFound("Trade " + id));
        if (cur.cancelled()) throw ApiException.conflict("Trade " + id + " is cancelled");
        if (cur.version() != expectedVersion) {
            throw ApiException.conflict("Trade " + id + " was changed by someone else (now version " + cur.version() + "); reload it");
        }
        return cur;
    }

    private void supersede(String id, int version, Instant now) {
        jdbc.sql("UPDATE trade_versions SET superseded_at = :now WHERE id = :id AND version = :v")
                .param("now", Timestamp.from(now)).param("id", id).param("v", version).update();
    }

    private Version insert(String id, int version, Request r, String user, String reason, boolean cancelled, Instant now) {
        LocalDate settle = r.settleDate() != null ? r.settleDate() : plusBusinessDays(r.tradeDate(), 2);
        if (settle.isBefore(r.tradeDate())) throw ApiException.badRequest("settleDate cannot be before tradeDate");
        BigDecimal commission = r.commission() == null ? BigDecimal.ZERO : r.commission();
        jdbc.sql("""
                INSERT INTO trade_versions (id, version, fund_id, security_id, side, quantity, price, commission, trade_date,
                                            settle_date, recorded_at, cancelled, entered_by, reason)
                VALUES (:id, :v, :f, :s, :side, :q, :p, :c, :td, :sd, :now, :cancelled, :user, :reason)""")
                .param("id", id).param("v", version).param("f", r.fundId()).param("s", r.securityId()).param("side", r.side())
                .param("q", r.quantity()).param("p", r.price()).param("c", commission).param("td", r.tradeDate())
                .param("sd", settle).param("now", Timestamp.from(now)).param("cancelled", cancelled).param("user", user)
                .param("reason", reason).update();
        Version v = new Version(id, version, r.fundId(), r.securityId(), r.side(), r.quantity(), r.price(), commission,
                r.tradeDate(), settle, now, null, cancelled, user, reason);
        // Keyed by fund so all of a fund's events stay in order on one partition.
        jdbc.sql("INSERT INTO outbox (topic, message_key, payload) VALUES (:t, :k, CAST(:p AS jsonb))")
                .param("t", TOPIC).param("k", r.fundId()).param("p", json.writeValueAsString(v)).update();
        return v;
    }

    static LocalDate plusBusinessDays(LocalDate d, int days) {
        LocalDate x = d;
        for (int i = 0; i < days; ) {
            x = x.plusDays(1);
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) i++;
        }
        return x;
    }

    private static Version row(ResultSet rs, int i) throws SQLException {
        Timestamp sup = rs.getTimestamp("superseded_at");
        return new Version(rs.getString("id"), rs.getInt("version"), rs.getString("fund_id"), rs.getString("security_id"),
                rs.getString("side"), rs.getBigDecimal("quantity"), rs.getBigDecimal("price"), rs.getBigDecimal("commission"),
                rs.getObject("trade_date", LocalDate.class), rs.getObject("settle_date", LocalDate.class),
                rs.getTimestamp("recorded_at").toInstant(), sup == null ? null : sup.toInstant(), rs.getBoolean("cancelled"),
                rs.getString("entered_by"), rs.getString("reason"));
    }

}
