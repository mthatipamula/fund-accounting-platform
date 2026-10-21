package com.fundaccounting.engine.model;

import java.time.LocalDate;
import java.util.List;

public record Fund(String id, String name, String baseCurrency, CostMethod costMethod, LocalDate inceptionDate,
                   List<ShareClass> shareClasses) {}
