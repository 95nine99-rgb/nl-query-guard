package dev.portfolio.nlquery.guard;

import dev.portfolio.nlquery.common.error.ErrorCode;


public class SqlGuardException  extends RuntimeException {
    private final ErrorCode errorCode;

    public SqlGuardException(ErrorCode errorCode) {
        super(errorCode.name());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
