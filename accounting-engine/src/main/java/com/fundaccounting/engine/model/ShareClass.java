package com.fundaccounting.engine.model;

import java.math.BigDecimal;

/** A share class: its own fee rate and NAV per share; all classes share the fund's portfolio. */
public record ShareClass(String id, String name, BigDecimal managementFeeRate, BigDecimal initialNavPerShare) {}
