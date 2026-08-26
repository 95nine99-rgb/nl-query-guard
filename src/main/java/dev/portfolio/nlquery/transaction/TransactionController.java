package dev.portfolio.nlquery.transaction;


import dev.portfolio.nlquery.transaction.dto.TransactionResponse;
import dev.portfolio.nlquery.transaction.dto.TransactionSearchRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionService transactionService;


    @GetMapping
    public List<TransactionResponse> searchTransactions(@Valid TransactionSearchRequest request, @PageableDefault(size = 20, sort = "transactionAt", direction = Sort.Direction.DESC) Pageable pageable) {

        // TODO: 인증 도입 시 SecurityContext 에서 꺼낸다.
        // LLM 이 만든 SQL 에 서버가 user_id 를 강제 주입하는 지점과 같은 경계 (ADR-002)
        return transactionService.search(1L, request, pageable);

    }


}

