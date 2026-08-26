package dev.portfolio.nlquery.transaction;


import dev.portfolio.nlquery.transaction.dto.TransactionResponse;
import dev.portfolio.nlquery.transaction.dto.TransactionSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;


    @Transactional(readOnly = true)
    public List<TransactionResponse> search(Long userId, TransactionSearchRequest request, Pageable pageable) {
        LocalDateTime from = request.from().atStartOfDay();
        LocalDateTime to = request.to().plusDays(1).atStartOfDay();

        return transactionRepository.
                findByUserIdAndCategoryIdAndTransactionAtBetween(userId, request.categoryId(), from, to, pageable).
                stream().map(TransactionResponse::from).toList();

    }

}
