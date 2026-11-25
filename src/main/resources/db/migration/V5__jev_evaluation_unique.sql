-- 같은 회차·도서의 JEV 평가 결과는 1건만 둔다. 재호출·동시 호출로 중복 저장되는 것을 DB 에서 막는다.
CREATE UNIQUE INDEX uq_book_evaluation_jev_session_book
  ON book_evaluation (scan_session_id, book_id)
  WHERE evaluation_type = 'JEV';

-- 평가 1건의 축별 결과를 조회할 때 쓴다.
CREATE INDEX idx_ecr_evaluation ON evaluation_criterion_result (evaluation_id);
