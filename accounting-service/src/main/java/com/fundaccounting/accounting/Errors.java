package com.fundaccounting.accounting;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ValuationService.MarketDataUnavailable.class)
    ProblemDetail marketData(ValuationService.MarketDataUnavailable e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
