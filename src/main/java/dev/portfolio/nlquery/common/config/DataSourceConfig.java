package dev.portfolio.nlquery.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * DataSource 2개를 명시적으로 정의한다.
 *
 * ADR-002 방어층 4 — LLM 이 만든 SQL 은 읽기 전용 계정으로만 실행한다.
 * 파서(방어층 1~2)를 뚫려도 DB 권한이 막는다.
 *
 * ⚠️ 왜 app 쪽까지 직접 정의하는가
 *   Spring Boot 의 DataSource 자동 설정은 @ConditionalOnMissingBean 이다.
 *   DataSource 빈을 하나라도 직접 만들면 자동 설정이 물러난다.
 *   queryDataSource 만 정의했더니 app 용 DataSource 가 생기지 않아
 *   JPA 가 query_ro 로 스키마를 검증하려다 실패했다(users 테이블 권한 없음).
 *   ddl-auto=validate 였기 때문에 기동 단계에서 바로 드러났다(ADR-003).
 *   → 둘 다 정의하고 app 쪽에 @Primary 를 붙인다.
 */
@Configuration
public class DataSourceConfig {

    /** 앱 계정 — 스키마 관리 + 로그 기록. JPA·기본 JdbcTemplate 이 사용 */
    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("app-pool");
        return ds;
    }

    /** 질의 실행 전용 — 읽기 권한만. transactions, categories 만 조회 가능 */
    @Bean
    public DataSource queryDataSource(
            @Value("${app.query-datasource.url}") String url,
            @Value("${app.query-datasource.username}") String username,
            @Value("${app.query-datasource.password}") String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("query-ro-pool");
        ds.setMaximumPoolSize(5);
        ds.setReadOnly(true);
        return ds;
    }

    /**
     * 앱 계정용 JdbcTemplate.
     *
     * ⚠️ DataSource 와 똑같은 함정에 두 번 걸렸다.
     *    JdbcTemplateAutoConfiguration 은 @ConditionalOnMissingBean(JdbcOperations.class) 다.
     *    아래 queryJdbcTemplate 을 직접 정의하는 순간 자동 설정이 물러나
     *    기본 jdbcTemplate 빈이 사라졌고 주입이 실패했다.
     *    → 이쪽도 명시적으로 만들고 @Primary 를 붙인다.
     *
     *    교훈: Spring Boot 자동 설정은 "내가 안 만들었을 때만" 만들어준다.
     *          같은 타입 빈을 하나라도 직접 만들면 그 자동 설정 전체가 물러난다.
     */
    @Bean
    @Primary
    public JdbcTemplate appJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate queryJdbcTemplate(@Qualifier("queryDataSource") DataSource queryDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(queryDataSource);
        jdbc.setQueryTimeout(3);   // app.guard.query-timeout-ms
        jdbc.setMaxRows(200);      // app.guard.max-rows
        return jdbc;
    }
}
