package dev.portfolio.nlquery.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.portfolio.nlquery.guard.SqlGuardException;

import dev.portfolio.nlquery.guard.JsqlUserIdEnforcer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ⚠️ 로컬 Ollama 서버가 떠 있어야 한다.
 *
 *   ollama serve
 *   ollama pull qwen2.5:3b
 *
 * CI 러너에는 Ollama 가 없으므로 @Tag("local-llm") 으로 분리하고
 * build.gradle 에서 제외한다. 로컬에서만 다음으로 실행한다.
 *
 *   ./gradlew test -PincludeLocalLlm
 */
@Tag("local-llm")
class OllamaLlmClientTest {
    @Test
    @DisplayName("LLM TESTING")
    void llmTest() throws IOException {
        LlmClient llmClient = new OllamaLlmClient("http://localhost:11434", "qwen2.5:3b");

        String template = Files.readString(Path.of("prompts/sql-select.v1.txt"));
        String prompt = template
                .replace("{{TODAY}}", "2026-09-02")
                .replace("{{QUERY}}", "3만원 넘게 쓴 거래 중에 쇼핑 카테고리만");

        String result = llmClient.generate(prompt);

        System.out.println("=== 결과 ===\n" + result);
    }


}
