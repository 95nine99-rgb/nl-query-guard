package dev.portfolio.nlquery.transaction.dto;

import dev.portfolio.nlquery.transaction.Transaction;

import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        String title,
        int amount,
        LocalDateTime transactionAt,    
        String categoryName
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(tx.getId(),
                tx.getTitle(),
                tx.getAmount(),
                tx.getTransactionAt(),
                tx.getCategory().getName()
        );
    }

}