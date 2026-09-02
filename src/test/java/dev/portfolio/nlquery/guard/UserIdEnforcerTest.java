package dev.portfolio.nlquery.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user_id 강제 주입 테스트.
 * <p>
 * Spring 을 띄우지 않는 순수 단위 테스트다. DB 도 필요 없다.
 * (CI 가 MySQL 없이도 이 테스트는 돌아간다 — 판단 ④ 와 관련)
 * <p>
 * 케이스 4개는 2026-08-31 측정에서 실제로 뚫린 것들에서 뽑았다.
 */
class UserIdEnforcerTest {

    private static final long LOGIN_USER_ID = 1L;

    //    private final UserIdEnforcer enforcer = new RegexUserIdEnforcer();
    private final UserIdEnforcer enforcer = new JsqlUserIdEnforcer();

    @Test
    @DisplayName("A. WHERE 에 다른 user_id 가 명시된 경우")
    void case_A_다른_user_id() {
        String sql = "SELECT * FROM transactions WHERE user_id = 2";
        assertThatThrownBy(() -> enforcer.enforce(sql, LOGIN_USER_ID))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    @DisplayName("B. WHERE 절이 아예 없는 경우")
    void case_B_WHERE_없음() {
        String sql = "SeLeCt  *  FrOm    transactions";
        String actual = enforcer.enforce(sql, LOGIN_USER_ID);
        assertThat(actual.replaceAll("\\s+", "")).contains("user_id=1");
    }

    @Test
    @DisplayName("C. 주석으로 뒤가 잘린 경우 — 정규식은 여기서 실패할 것")
    void case_C_주석_우회() {
        String sql = "SELECT * FROM transactions WHERE user_id=1 -- AND category_id=1";
        assertThatThrownBy(() -> enforcer.enforce(sql, LOGIN_USER_ID))
                .isInstanceOf(SqlGuardException.class);

    }

    @Test
    @DisplayName("D. WHERE 뒤에 ORDER BY 가 붙은 경우")
    void case_D_ORDER_BY() {
        String given = "SELECT SUM(amount) FROM transactions WHERE category_id=3 ORDER BY id";
        String actual = enforcer.enforce(given, LOGIN_USER_ID);
        String normalized = actual.replaceAll("\\s+", "");
        assertThat(normalized).contains("user_id=1");
    }

    @Test
    @DisplayName("E. 문자열 안에 -- 가 들어간 경우")
    void case_E_문자열_리터럴() {
        String given = "SELECT * FROM transactions WHERE title = '스타벅스--강남점'";
        String actual = enforcer.enforce(given, 1L);
        String normalized = actual.replaceAll("\\s+", "");
        assertThat(normalized).contains("스타벅스--강남점");
    }

    @Test
    @DisplayName("F. 블록 주석")
    void case_F_블록주석() {
        String sql = "SELECT * FROM transactions WHERE /* 무시 */ user_id=2";
        assertThatThrownBy(() -> enforcer.enforce(sql, LOGIN_USER_ID))
                .isInstanceOf(SqlGuardException.class);
    }

    @Test
    @DisplayName("G. WHERE 가 소문자")
    void case_G_소문자() {
        String sql = "SELECT * FROM transactions where user_id = 2";
        assertThatThrownBy(() -> enforcer.enforce(sql, LOGIN_USER_ID))
                .isInstanceOf(SqlGuardException.class);
    }
    @Test
    void 서브쿼리로_user_id_우회() {
        String given = "SELECT * FROM transactions WHERE user_id IN (SELECT 2)";

        assertThatThrownBy(() -> new JsqlUserIdEnforcer().enforce(given, 1L))
                .isInstanceOf(SqlGuardException.class);
    }
}
