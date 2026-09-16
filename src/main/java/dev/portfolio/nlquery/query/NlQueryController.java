package dev.portfolio.nlquery.query;


import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/nl-query")
@RequiredArgsConstructor
public class NlQueryController {

    private final NlQueryService nlQueryService;

    public record AskRequest(String question) {
    }

    private static final long LOGIN_USER_ID = 1L;   // 인증 미구현. 데모용 고정값

    @PostMapping
    public NlQueryResult ask(@RequestBody AskRequest request) throws IOException {

        NlQueryResult result = nlQueryService.ask(request.question(), LOGIN_USER_ID);
        return result;
    }
}
