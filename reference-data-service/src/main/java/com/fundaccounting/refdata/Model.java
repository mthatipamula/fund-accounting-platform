package com.fundaccounting.refdata;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** API shapes for reference data. */
public final class Model {
    private Model() {}

    public record SecurityDto(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+") String id,
            @NotBlank @Size(max = 40) String symbol,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Pattern(regexp = "EQUITY|BOND") String assetClass,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            BigDecimal couponRate, Integer couponsPerYear, LocalDate issueDate, LocalDate maturityDate,
            @Pattern(regexp = "THIRTY_360|ACT_365") String dayCount) {}

    public record PriceDto(@NotBlank String securityId, @NotNull LocalDate date,
                           @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price, String source) {}

    public record PriceRow(String securityId, LocalDate date, BigDecimal price, String source, Instant recordedAt, Instant supersededAt) {}

    public record FxDto(@NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
                        @NotNull @Pattern(regexp = "[A-Z]{3}") String baseCurrency, @NotNull LocalDate date,
                        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal rate) {}

    public record FxRow(String currency, String baseCurrency, LocalDate date, BigDecimal rate, Instant recordedAt, Instant supersededAt) {}

    public record CorporateActionDto(@NotBlank String securityId, @NotNull @Pattern(regexp = "CASH_DIVIDEND|SPLIT") String type,
                                     @NotNull LocalDate exDate, @NotNull LocalDate payDate, BigDecimal amountPerShare,
                                     BigDecimal ratio) {}

    public record CorporateActionRow(String id, int version, String securityId, String type, LocalDate exDate,
                                     LocalDate payDate, BigDecimal amountPerShare, BigDecimal ratio, Instant recordedAt,
                                     Instant supersededAt, boolean cancelled) {}

    public record UploadResult(int loaded, int corrected) {}
}
