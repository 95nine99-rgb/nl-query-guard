package dev.portfolio.nlquery.query;

import java.util.List;
import java.util.Map;

/**
 * 자연어 질의 처리 결과.
 *
 * rawSql 과 executedSql 을 둘 다 담는다.
 * "LLM 이 뭘 만들었고, 서버가 뭘로 바꿨는가" 가 이 프로젝트의 핵심이라
 * 응답에서 그 대비가 보여야 한다.
 *
 * ⚠️ 운영이라면 SQL 을 노출하지 않는다. 내부 스키마가 새어 나간다.
 *    데모 목적의 선택이다. docs/06-retro.md 참조.
 */
public record NlQueryResult(
        String status,        // OK | BLOCKED
        String message,       // 사용자에게 보여줄 문구
        String errorCode,     // 시스템용. 차단 사유 집계에 쓴다
        String rawSql,        // LLM 원본 (마지막 시도)
        String executedSql,   // 검증·주입 후 실제 실행된 SQL
        int attempts,         // 몇 번째 시도에 성공/실패했는가
        List<Map<String, Object>> rows
) {

    public static NlQueryResult ok(String rawSql, String executedSql, int attempts,
                                   List<Map<String, Object>> rows) {
        return new NlQueryResult("OK", null, null, rawSql, executedSql, attempts, rows);
    }

    public static NlQueryResult blocked(dev.portfolio.nlquery.common.error.ErrorCode code,
                                        String rawSql, int attempts) {
        return new NlQueryResult("BLOCKED", code.getMessage(), code.name(),
                rawSql, null, attempts, List.of());
    }
}
