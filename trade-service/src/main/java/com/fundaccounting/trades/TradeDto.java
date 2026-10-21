package com.fundaccounting.trades;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class TradeDto {
    private TradeDto() {}

    public record Request(@NotBlank @Size(max = 40) String fundId, @NotBlank @Size(max = 40) String securityId,
                          @NotNull @Pattern(regexp = "BUY|SELL") String side,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price,
                          @DecimalMin("0") BigDecimal commission, @NotNull LocalDate tradeDate, LocalDate settleDate,
                          @Size(max = 500) String reason) {}

    /** Amend or cancel: the version the user was looking at, so a concurrent change is detected (409). */
    public record Revision(@NotNull Integer expectedVersion, @NotBlank @Size(max = 500) String reason, Request trade) {}

    public record Version(String id, int version, String fundId, String securityId, String side, BigDecimal quantity,
                          BigDecimal price, BigDecimal commission, LocalDate tradeDate, LocalDate settleDate,
                          Instant recordedAt, Instant supersededAt, boolean cancelled, String enteredBy, String reason) {}
}
