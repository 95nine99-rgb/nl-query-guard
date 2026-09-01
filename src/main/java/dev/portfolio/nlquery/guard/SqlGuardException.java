package dev.portfolio.nlquery.guard;

public class SqlGuardException  extends RuntimeException {
    public SqlGuardException(String message) {
        super(message);   // 부모(RuntimeException)에게 메시지 전달
    }
}
