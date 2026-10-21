package com.fundaccounting.refdata;

import com.fundaccounting.common.ApiErrors.ApiException;
import com.fundaccounting.refdata.Model.CorporateActionDto;
import com.fundaccounting.refdata.Model.CorporateActionRow;
import com.fundaccounting.refdata.Model.FxDto;
import com.fundaccounting.refdata.Model.FxRow;
import com.fundaccounting.refdata.Model.PriceDto;
import com.fundaccounting.refdata.Model.PriceRow;
import com.fundaccounting.refdata.Model.SecurityDto;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Bitemporal storage. A correction (a new price for a date that already has one, or an amended corporate action)
 * closes the current row with superseded_at = now and inserts the new row with recorded_at = now, in one transaction.
 */
@Repository
public class ReferenceDataRepository {
    private final JdbcClient jdbc;
    private final Clock clock;

    public ReferenceDataRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- securities

    public List<SecurityDto> securities(Collection<String> ids) {
        String where = ids == null || ids.isEmpty() ? "" : " WHERE id = ANY(:ids)";
        var q = jdbc.sql("""
                SELECT id, symbol, name, asset_class, currency, coupon_rate, coupons_per_year, issue_date, maturity_date, day_count
                FROM securities""" + where + " ORDER BY asset_class DESC, symbol");
        if (!where.isEmpty()) q = q.param("ids", ids.toArray(String[]::new));
        return q.query((rs, i) -> new SecurityDto(rs.getString("id"), rs.getString("symbol"), rs.getString("name"),
                rs.getString("asset_class"), rs.getString("currency").trim(), rs.getBigDecimal("coupon_rate"),
                (Integer) rs.getObject("coupons_per_year"), rs.getObject("issue_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class), rs.getString("day_count"))).list();
    }

    public void createSecurity(SecurityDto s) {
        if ("BOND".equals(s.assetClass()) && (s.couponRate() == null || s.couponsPerYear() == null || s.issueDate() == null
                || s.maturityDate() == null || s.dayCount() == null)) {
            throw ApiException.badRequest("Bonds need couponRate, couponsPerYear, issueDate, maturityDate and dayCount");
        }
        jdbc.sql("""
                INSERT INTO securities (id, symbol, name, asset_class, currency, coupon_rate, coupons_per_year, issue_date, maturity_date, day_count)
                VALUES (:id, :symbol, :name, :ac, :ccy, :cr, :cpy, :issue, :maturity, :dc)""")
                .param("id", s.id()).param("symbol", s.symbol()).param("name", s.name()).param("ac", s.assetClass())
                .param("ccy", s.currency()).param("cr", s.couponRate()).param("cpy", s.couponsPerYear())
                .param("issue", s.issueDate()).param("maturity", s.maturityDate()).param("dc", s.dayCount()).update();
    }

    // ---------------------------------------------------------------- prices

    /** Saves prices; a price for a date that already has one is a correction. Returns how many were corrections. */
    @Transactional
    public int savePrices(List<PriceDto> prices) {
        Timestamp now = Timestamp.from(clock.instant());
        int corrected = 0;
        for (PriceDto p : prices) {
            corrected += jdbc.sql("UPDATE prices SET superseded_at = :now WHERE security_id = :s AND price_date = :d AND superseded_at IS NULL")
                    .param("now", now).param("s", p.securityId()).param("d", p.date()).update();
            jdbc.sql("INSERT INTO prices (security_id, price_date, price, source, recorded_at) VALUES (:s, :d, :p, :src, :now)")
                    .param("s", p.securityId()).param("d", p.date()).param("p", p.price())
                    .param("src", p.source() == null ? "MANUAL" : p.source()).param("now", now).update();
        }
        return corrected;
    }

    /** Prices as known at a moment, for a date range (inclusive). */
    public List<PriceRow> prices(Collection<String> securityIds, LocalDate from, LocalDate to, Instant knownAt) {
        return jdbc.sql("""
                SELECT security_id, price_date, price, source, recorded_at, superseded_at FROM prices
                WHERE (cardinality(:ids) = 0 OR security_id = ANY(:ids)) AND price_date BETWEEN :from AND :to
                  AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)
                ORDER BY security_id, price_date""")
                .param("ids", securityIds.toArray(String[]::new)).param("from", from).param("to", to)
                .param("k", Timestamp.from(knownAt))
                .query((rs, i) -> new PriceRow(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant(), ts(rs.getTimestamp(6)))).list();
    }

    /** Every version of one security's price on one date: the audit trail of corrections. */
    public List<PriceRow> priceHistory(String securityId, LocalDate date) {
        return jdbc.sql("""
                SELECT security_id, price_date, price, source, recorded_at, superseded_at FROM prices
                WHERE security_id = :s AND price_date = :d ORDER BY recorded_at""")
                .param("s", securityId).param("d", date)
                .query((rs, i) -> new PriceRow(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant(), ts(rs.getTimestamp(6)))).list();
    }

    // ---------------------------------------------------------------- fx

    @Transactional
    public int saveFx(List<FxDto> rates) {
        Timestamp now = Timestamp.from(clock.instant());
        int corrected = 0;
        for (FxDto r : rates) {
            corrected += jdbc.sql("""
                    UPDATE fx_rates SET superseded_at = :now
                    WHERE currency = :c AND base_currency = :b AND rate_date = :d AND superseded_at IS NULL""")
                    .param("now", now).param("c", r.currency()).param("b", r.baseCurrency()).param("d", r.date()).update();
            jdbc.sql("INSERT INTO fx_rates (currency, base_currency, rate_date, rate, recorded_at) VALUES (:c, :b, :d, :r, :now)")
                    .param("c", r.currency()).param("b", r.baseCurrency()).param("d", r.date()).param("r", r.rate())
                    .param("now", now).update();
        }
        return corrected;
    }

    public List<FxRow> fx(Collection<String> currencies, String base, LocalDate from, LocalDate to, Instant knownAt) {
        return jdbc.sql("""
                SELECT currency, base_currency, rate_date, rate, recorded_at, superseded_at FROM fx_rates
                WHERE (cardinality(:c) = 0 OR currency = ANY(:c)) AND base_currency = :b AND rate_date BETWEEN :from AND :to
                  AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)
                ORDER BY currency, rate_date""")
                .param("c", currencies.toArray(String[]::new)).param("b", base).param("from", from).param("to", to)
                .param("k", Timestamp.from(knownAt))
                .query((rs, i) -> new FxRow(rs.getString(1).trim(), rs.getString(2).trim(), rs.getObject(3, LocalDate.class),
                        rs.getBigDecimal(4), rs.getTimestamp(5).toInstant(), ts(rs.getTimestamp(6)))).list();
    }

    // ---------------------------------------------------------------- corporate actions

    public String createCorporateAction(CorporateActionDto ca) {
        validate(ca);
        String id = "CA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        insertAction(id, 1, ca, Timestamp.from(clock.instant()), false);
        return id;
    }

    /** Amends or cancels a corporate action as a new version; the old version stays in history. */
    @Transactional
    public void reviseCorporateAction(String id, CorporateActionDto ca, boolean cancel) {
        CorporateActionRow current = jdbc.sql("""
                SELECT id, version, security_id, type, ex_date, pay_date, amount_per_share, ratio, recorded_at, superseded_at, cancelled
                FROM corporate_actions WHERE id = :id AND superseded_at IS NULL FOR UPDATE""")
                .param("id", id).query(ReferenceDataRepository::actionRow).optional()
                .orElseThrow(() -> ApiException.notFound("Corporate action " + id));
        if (current.cancelled()) throw ApiException.conflict("Corporate action " + id + " is already cancelled");
        CorporateActionDto next = cancel ? new CorporateActionDto(current.securityId(), current.type(), current.exDate(),
                current.payDate(), current.amountPerShare(), current.ratio()) : ca;
        validate(next);
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("UPDATE corporate_actions SET superseded_at = :now WHERE id = :id AND version = :v")
                .param("now", now).param("id", id).param("v", current.version()).update();
        insertAction(id, current.version() + 1, next, now, cancel);
    }

    /** All versions, including superseded and cancelled ones; the caller (or gRPC) filters by known-at. */
    public List<CorporateActionRow> corporateActions(Collection<String> securityIds) {
        return jdbc.sql("""
                SELECT id, version, security_id, type, ex_date, pay_date, amount_per_share, ratio, recorded_at, superseded_at, cancelled
                FROM corporate_actions WHERE cardinality(:ids) = 0 OR security_id = ANY(:ids) ORDER BY ex_date, id, version""")
                .param("ids", securityIds.toArray(String[]::new)).query(ReferenceDataRepository::actionRow).list();
    }

    private void insertAction(String id, int version, CorporateActionDto ca, Timestamp now, boolean cancelled) {
        jdbc.sql("""
                INSERT INTO corporate_actions (id, version, security_id, type, ex_date, pay_date, amount_per_share, ratio, recorded_at, cancelled)
                VALUES (:id, :v, :s, :t, :ex, :pay, :amt, :ratio, :now, :cancelled)""")
                .param("id", id).param("v", version).param("s", ca.securityId()).param("t", ca.type())
                .param("ex", ca.exDate()).param("pay", ca.payDate()).param("amt", ca.amountPerShare())
                .param("ratio", ca.ratio()).param("now", now).param("cancelled", cancelled).update();
    }

    private static void validate(CorporateActionDto ca) {
        if ("CASH_DIVIDEND".equals(ca.type()) && (ca.amountPerShare() == null || ca.amountPerShare().signum() <= 0)) {
            throw ApiException.badRequest("A cash dividend needs a positive amountPerShare");
        }
        if ("SPLIT".equals(ca.type()) && (ca.ratio() == null || ca.ratio().signum() <= 0)) {
            throw ApiException.badRequest("A split needs a positive ratio (2 for a 2-for-1 split)");
        }
        if (ca.payDate().isBefore(ca.exDate())) throw ApiException.badRequest("payDate cannot be before exDate");
    }

    private static CorporateActionRow actionRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CorporateActionRow(rs.getString("id"), rs.getInt("version"), rs.getString("security_id"), rs.getString("type"),
                rs.getObject("ex_date", LocalDate.class), rs.getObject("pay_date", LocalDate.class),
                rs.getBigDecimal("amount_per_share"), rs.getBigDecimal("ratio"), rs.getTimestamp("recorded_at").toInstant(),
                ts(rs.getTimestamp("superseded_at")), rs.getBoolean("cancelled"));
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
}
