-- 로그인 세션과 Refresh Token 저장소를 Redis 로 옮겨 더 이상 쓰지 않는 테이블을 제거한다.
DROP TABLE IF EXISTS auth_session;
