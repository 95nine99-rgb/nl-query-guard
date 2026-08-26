package dev.portfolio.nlquery.transaction;


import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByUserIdAndCategoryIdAndTransactionAtBetween(
            Long userId, Long categoryId, LocalDateTime from, LocalDateTime to,
            Pageable pageable);
}
