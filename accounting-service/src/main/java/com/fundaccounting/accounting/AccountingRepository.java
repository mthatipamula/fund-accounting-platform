package com.fundaccounting.accounting;

import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.engine.model.CapitalActivity;
import com.fundaccounting.engine.model.CostMethod;
import com.fundaccounting.engine.model.Fund;
import com.fundaccounting.engine.model.ShareClass;
import com.fundaccounting.engine.model.Side;
import com.fundaccounting.engine.model.Trade;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountingRepository {
    private final JdbcClient jdbc;

    public AccountingRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---------------------------------------------------------------- funds

    public record FundRow(String id, String name, String baseCurrency, String costMethod, LocalDate inceptionDate) {}

    public List<FundRow> funds() {
        return jdbc.sql("SELECT id, name, base_currency, cost_method, inception_date FROM funds ORDER BY name")
                .query((rs, i) -> new FundRow(rs.getString(1), rs.getString(2), rs.getString(3).trim(), rs.getString(4),
                        rs.getObject(5, LocalDate.class))).list();
    }

    public Fund fund(String id) {
        FundRow f = funds().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Fund " + id));
        List<ShareClass> classes = jdbc.sql("""
                SELECT id, name, management_fee_rate, initial_nav_per_share FROM share_classes WHERE fund_id = :f ORDER BY name""")
                .param("f", id).query((rs, i) -> new ShareClass(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4))).list();
        return new Fund(f.id(), f.name(), f.baseCurrency(), CostMethod.valueOf(f.costMethod()), f.inceptionDate(), classes);
    }

    @Transactional
    public void createFund(FundRow f, List<ShareClass> classes) {
        jdbc.sql("INSERT INTO funds (id, name, base_currency, cost_method, inception_date) VALUES (:id, :n, :c, :m, :d)")
                .param("id", f.id()).param("n", f.name()).param("c", f.baseCurrency()).param("m", f.costMethod())
                .param("d", f.inceptionDate()).update();
        for (ShareClass c : classes) {
            jdbc.sql("""
                    INSERT INTO share_classes (id, fund_id, name, management_fee_rate, initial_nav_per_share)
                    VALUES (:id, :f, :n, :r, :nav)""")
                    .param("id", c.id()).param("f", f.id()).param("n", c.name()).param("r", c.managementFeeRate())
                    .param("nav", c.initialNavPerShare()).update();
        }
    }

    // ---------------------------------------------------------------- investors

    public record Investor(String id, String name, String type) {}

    public List<Investor> investors() {
        return jdbc.sql("SELECT id, name, type FROM investors ORDER BY name")
                .query((rs, i) -> new Investor(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    public void createInvestor(Investor inv) {
        jdbc.sql("INSERT INTO investors (id, name, type) VALUES (:id, :n, :t)")
                .param("id", inv.id()).param("n", inv.name()).param("t", inv.type()).update();
    }

    // ---------------------------------------------------------------- capital activity (bitemporal)

    public record CapitalRow(String id, int version, String fundId, String shareClassId, String investorId, String type,
                             LocalDate dealingDate, BigDecimal amount, BigDecimal shares, Instant recordedAt,
                             Instant supersededAt, boolean cancelled, String enteredBy) {
        CapitalActivity toEngine() {
            return new CapitalActivity(id, version, fundId, shareClassId, investorId, CapitalActivity.Type.valueOf(type),
                    dealingDate, amount, shares, recordedAt, supersededAt, cancelled);
        }
    }

    private static final String CAPITAL = """
            SELECT id, version, fund_id, share_class_id, investor_id, type, dealing_date, amount, shares, recorded_at,
                   superseded_at, cancelled, entered_by FROM capital_versions""";

    public List<CapitalRow> capitalAsKnownAt(String fundId, Instant knownAt) {
        return jdbc.sql(CAPITAL + " WHERE fund_id = :f AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k) ORDER BY dealing_date, id")
                .param("f", fundId).param("k", Timestamp.from(knownAt)).query(AccountingRepository::capital).list();
    }

    public Optional<CapitalRow> currentCapital(String id, boolean lock) {
        return jdbc.sql(CAPITAL + " WHERE id = :id AND superseded_at IS NULL" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(AccountingRepository::capital).optional();
    }

    public void insertCapital(CapitalRow c) {
        jdbc.sql("""
                INSERT INTO capital_versions (id, version, fund_id, share_class_id, investor_id, type, dealing_date, amount,
                                              shares, recorded_at, cancelled, entered_by)
                VALUES (:id, :v, :f, :sc, :inv, :t, :d, :a, :s, :at, :c, :by)""")
                .param("id", c.id()).param("v", c.version()).param("f", c.fundId()).param("sc", c.shareClassId())
                .param("inv", c.investorId()).param("t", c.type()).param("d", c.dealingDate()).param("a", c.amount())
                .param("s", c.shares()).param("at", Timestamp.from(c.recordedAt())).param("c", c.cancelled())
                .param("by", c.enteredBy()).update();
    }

    public void supersedeCapital(String id, int version, Instant at) {
        jdbc.sql("UPDATE capital_versions SET superseded_at = :at WHERE id = :id AND version = :v")
                .param("at", Timestamp.from(at)).param("id", id).param("v", version).update();
    }

    private static CapitalRow capital(ResultSet rs, int i) throws SQLException {
        Timestamp sup = rs.getTimestamp("superseded_at");
        return new CapitalRow(rs.getString("id"), rs.getInt("version"), rs.getString("fund_id"), rs.getString("share_class_id"),
                rs.getString("investor_id"), rs.getString("type"), rs.getObject("dealing_date", LocalDate.class),
                rs.getBigDecimal("amount"), rs.getBigDecimal("shares"), rs.getTimestamp("recorded_at").toInstant(),
                sup == null ? null : sup.toInstant(), rs.getBoolean("cancelled"), rs.getString("entered_by"));
    }

    // ---------------------------------------------------------------- trade events (from Kafka)

    public record TradeEvent(String id, int version, String fundId, String securityId, String side, BigDecimal quantity,
                             BigDecimal price, BigDecimal commission, LocalDate tradeDate, LocalDate settleDate,
                             Instant recordedAt, boolean cancelled) {}

    /** Idempotent: a redelivered event is ignored; an older version is closed when a newer one arrives. */
    @Transactional
    public void upsertTradeEvent(TradeEvent e) {
        int inserted = jdbc.sql("""
                INSERT INTO trade_events (id, version, fund_id, security_id, side, quantity, price, commission, trade_date,
                                          settle_date, recorded_at, cancelled)
                VALUES (:id, :v, :f, :s, :side, :q, :p, :c, :td, :sd, :at, :cancelled)
                ON CONFLICT (id, version) DO NOTHING""")
                .param("id", e.id()).param("v", e.version()).param("f", e.fundId()).param("s", e.securityId())
                .param("side", e.side()).param("q", e.quantity()).param("p", e.price()).param("c", e.commission())
                .param("td", e.tradeDate()).param("sd", e.settleDate()).param("at", Timestamp.from(e.recordedAt()))
                .param("cancelled", e.cancelled()).update();
        if (inserted == 0) return;
        jdbc.sql("UPDATE trade_events SET superseded_at = :at WHERE id = :id AND version < :v AND superseded_at IS NULL")
                .param("at", Timestamp.from(e.recordedAt())).param("id", e.id()).param("v", e.version()).update();
    }

    public List<Trade> tradesAsKnownAt(String fundId, Instant knownAt) {
        return jdbc.sql("""
                SELECT id, version, fund_id, security_id, side, quantity, price, commission, trade_date, settle_date,
                       recorded_at, superseded_at, cancelled
                FROM trade_events WHERE fund_id = :f AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)
                  AND NOT cancelled""")
                .param("f", fundId).param("k", Timestamp.from(knownAt))
                .query((rs, i) -> {
                    Timestamp sup = rs.getTimestamp("superseded_at");
                    return new Trade(rs.getString("id"), rs.getInt("version"), rs.getString("fund_id"), rs.getString("security_id"),
                            Side.valueOf(rs.getString("side")), rs.getBigDecimal("quantity"), rs.getBigDecimal("price"),
                            rs.getBigDecimal("commission"), rs.getObject("trade_date", LocalDate.class),
                            rs.getObject("settle_date", LocalDate.class), rs.getTimestamp("recorded_at").toInstant(),
                            sup == null ? null : sup.toInstant(), rs.getBoolean("cancelled"));
                }).list();
    }

    // ---------------------------------------------------------------- period close

    public record PeriodClose(long id, String fundId, LocalDate closedThrough, String action, Instant performedAt,
                              String performedBy, BigDecimal nav) {}

    public Optional<PeriodClose> currentClose(String fundId) {
        return periodHistory(fundId).stream().findFirst().filter(p -> p.closedThrough() != null);
    }

    public List<PeriodClose> periodHistory(String fundId) {
        return jdbc.sql("""
                SELECT id, fund_id, closed_through, action, performed_at, performed_by, nav FROM period_closes
                WHERE fund_id = :f ORDER BY id DESC""")
                .param("f", fundId).query((rs, i) -> new PeriodClose(rs.getLong(1), rs.getString(2),
                        rs.getObject(3, LocalDate.class), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6),
                        rs.getBigDecimal(7))).list();
    }

    public void insertPeriodClose(String fundId, LocalDate closedThrough, String action, Instant at, String by, BigDecimal nav) {
        jdbc.sql("""
                INSERT INTO period_closes (fund_id, closed_through, action, performed_at, performed_by, nav)
                VALUES (:f, :d, :a, :at, :by, :nav)""")
                .param("f", fundId).param("d", closedThrough).param("a", action).param("at", Timestamp.from(at))
                .param("by", by).param("nav", nav).update();
    }

    public void outbox(String topic, String key, String payload) {
        jdbc.sql("INSERT INTO outbox (topic, message_key, payload) VALUES (:t, :k, CAST(:p AS jsonb))")
                .param("t", topic).param("k", key).param("p", payload).update();
    }
}
