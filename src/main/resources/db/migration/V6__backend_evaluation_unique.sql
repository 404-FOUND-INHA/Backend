-- 같은 회차·도서의 Backend 종합 평가는 현재 결과 1건만 둔다(다시 실행하면 이 행을 갱신한다).
-- 동시에 실행해도 중복 행이 생기지 않게 DB 에서도 막는다.
CREATE UNIQUE INDEX uq_book_evaluation_backend_session_book
  ON book_evaluation (scan_session_id, book_id)
  WHERE evaluation_type = 'BACKEND';
