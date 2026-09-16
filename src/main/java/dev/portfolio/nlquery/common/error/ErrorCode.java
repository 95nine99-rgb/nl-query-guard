package dev.portfolio.nlquery.common.error;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    LLM_WROTE_USER_ID("해당 조회는 이용할 수 없습니다"),
    NOT_SELECT("해당 조회는 이용할 수 없습니다"),
    UNPARSEABLE_SQL("질문을 다시 입력해주세요"),
    DB_PERMISSION_DENIED("해당 조회는 이용할 수 없습니다"),
    QUERY_TIMEOUT("조회 시간이 초과되었습니다"),
    NO_WHERE_CLAUSE("질문을 다시 입력해주세요");
    private final String message;


    // 생성자, getter
}
