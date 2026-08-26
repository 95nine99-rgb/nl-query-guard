-- 스키마 단일 진실 (ADR-003)
--
-- 실행:
--   docker exec -i nlq-mysql mysql -uroot -prootpw nlquery < infra/schema.sql
--
-- Hibernate 는 이 파일을 읽지 않는다. MySQL 의 실제 상태를 조회해
-- 엔티티와 대조할 뿐이다(ddl-auto=validate).
-- 이 파일을 고쳤으면 반드시 위 명령으로 적용해야 한다.
--
-- ⚠️ 성능용 인덱스는 여기 넣지 않는다.
--    8/26 실험이 "단일 인덱스 vs 복합 인덱스" before/after 측정이라,
--    미리 걸면 before 를 만들 수 없다.
--    FK 로 인해 자동 생성되는 단일 인덱스가 before 조건이 된다.

CREATE TABLE IF NOT EXISTS users (
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS categories (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '카페 식비 교통 등. 자연어 질의의 핵심 필터',
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transactions (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_id        BIGINT       NOT NULL COMMENT '권한 경계. 서버가 강제 주입한다',
    category_id    BIGINT       NOT NULL,
    title          VARCHAR(255) NOT NULL,
    amount         INT          NOT NULL,
    transaction_at DATETIME     NOT NULL COMMENT '비즈니스 시각이라 TIMESTAMP 가 아닌 DATETIME',
    PRIMARY KEY (id),
    CONSTRAINT fk_tx_user     FOREIGN KEY (user_id)     REFERENCES users (id),
    CONSTRAINT fk_tx_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8/26 에 추가할 것 (지금 실행하지 말 것):
--   CREATE INDEX idx_tx_user_cat_time ON transactions (user_id, category_id, transaction_at);
