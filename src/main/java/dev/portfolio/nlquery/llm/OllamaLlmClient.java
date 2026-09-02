package dev.portfolio.nlquery.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaLlmClient implements LlmClient {

    private final RestClient restClient;
    private final String model;

    public OllamaLlmClient(@Value("${app.llm.ollama.base-url}") String baseUrl,
                           @Value("${app.llm.ollama.model}") String model) {

        this.restClient = RestClient.create(baseUrl);
        this.model = model;
    }

    @Override
    public String generate(String prompt) {
        OllamaResponse res = restClient.post()
                .uri("/api/generate")
                .body(new OllamaRequest(model, prompt, false))
                .retrieve()
                .body(OllamaResponse.class);

        return res.response().trim();
    }

    record OllamaRequest(String model, String prompt, boolean stream) {}
    record OllamaResponse(String response, boolean done) {}

}
