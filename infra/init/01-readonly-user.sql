-- 첫 기동 시 1회 실행 (데이터 디렉터리가 비어 있을 때만)
--
-- 여기서는 계정만 만든다. GRANT 는 하지 않는다.
-- 이유: MySQL 은 존재하지 않는 테이블에 GRANT 를 걸 수 없다 (ERROR 1146).
--       테이블은 8/24 에 JPA 가 만든다. 그래서 권한 부여는 그 뒤로 미룬다.
--       → infra/grant-readonly.sql

CREATE USER IF NOT EXISTS 'query_ro'@'%' IDENTIFIED BY 'ropw';
FLUSH PRIVILEGES;
