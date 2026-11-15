-- 동시 수정 감지(낙관적 락)용 버전 컬럼. 기존 행은 0으로 초기화된다.
ALTER TABLE app_user ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE book     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN app_user.version IS '낙관적 락 버전';
COMMENT ON COLUMN book.version     IS '낙관적 락 버전';
