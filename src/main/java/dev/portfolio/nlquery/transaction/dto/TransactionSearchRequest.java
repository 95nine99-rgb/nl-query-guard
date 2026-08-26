package dev.portfolio.nlquery.transaction.dto;


import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record TransactionSearchRequest(
        @NotNull Long categoryId,
        @NotNull LocalDate from,
        @NotNull LocalDate to
) {
    public TransactionSearchRequest {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from이 to보다 늦습니다");
        }
    }
}
