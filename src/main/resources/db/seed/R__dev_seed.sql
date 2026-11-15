-- 개발용 시드 데이터 (dev 프로필에서만 적용). 운영 DB에는 절대 적용하지 않는다.
-- Repeatable 마이그레이션이라 파일이 바뀌면 다시 실행되므로 모든 INSERT 는 중복 안전하게 작성한다.
--
-- 로그인 계정 (비밀번호는 모두 Password1234!)
--   librarian@example.com  : 사서(LIBRARIAN)        - 도서 등록, 서가 설정 가능
--   worker@example.com     : 근로생(STUDENT_WORKER)

INSERT INTO app_user (email, password_hash, name, role, status, created_at, updated_at)
VALUES
  ('librarian@example.com', '$2a$10$7/YqDpMi1fJCU92GNzW0sOz218eRGqkalMWJuumjYrIDt8F/L/cc2', '김사서', 'LIBRARIAN',      'ACTIVE', now(), now()),
  ('worker@example.com',    '$2a$10$7/YqDpMi1fJCU92GNzW0sOz218eRGqkalMWJuumjYrIDt8F/L/cc2', '이근로', 'STUDENT_WORKER', 'ACTIVE', now(), now())
ON CONFLICT (email) DO NOTHING;

-- 서가 6개: 1층 3개(A구역), 2층 3개(B구역). 지도 좌표 단위는 cm 가정.
INSERT INTO shelf (shelf_code, zone, floor_no, shelf_no, level_no, call_number_start, call_number_end, marker_id,
                   map_x, map_y, map_z, width_cm, height_cm, depth_cm, rotation_degree, display_order, is_active,
                   created_at, updated_at)
VALUES
  ('A-01', 'A', 1, 1, NULL, '000',     '299.99', 'MK-A01',  100.00,  50.00, 0.00, 90.00, 200.00, 30.00,   0.00, 1, TRUE,  now(), now()),
  ('A-02', 'A', 1, 2, NULL, '300',     '499.99', 'MK-A02',  200.00,  50.00, 0.00, 90.00, 200.00, 30.00,   0.00, 2, TRUE,  now(), now()),
  ('A-03', 'A', 1, 3, NULL, '500',     '699.99', 'MK-A03',  300.00,  50.00, 0.00, 90.00, 200.00, 30.00,  90.00, 3, TRUE,  now(), now()),
  ('B-01', 'B', 2, 1, NULL, '700',     '799.99', 'MK-B01',  100.00, 150.00, 0.00, 90.00, 200.00, 30.00, 180.00, 4, TRUE,  now(), now()),
  ('B-02', 'B', 2, 2, NULL, '800',     '899.99', 'MK-B02',  200.00, 150.00, 0.00, 90.00, 200.00, 30.00, 180.00, 5, TRUE,  now(), now()),
  ('X-99', 'X', 2, 9, NULL, NULL,      NULL,     NULL,       NULL,   NULL,  NULL,  NULL,   NULL,  NULL,   NULL, 9, FALSE, now(), now())
ON CONFLICT (shelf_code) DO NOTHING;

-- 도서 16권. expected=정상 배치 서가, current=마지막 확인 서가 (다르면 오배치 상태).
-- ISBN 은 실제 도서가 아닌 가상의 값이다.
INSERT INTO book (registration_no, isbn, title, author, publisher, published_year, edition,
                  call_number_raw, classification_no, book_symbol, copy_number,
                  expected_shelf_id, current_shelf_id, lifecycle_status, loan_status,
                  last_loan_date, total_loan_count, book_width_cm, created_at, updated_at)
SELECT v.registration_no, v.isbn, v.title, v.author, v.publisher, v.published_year, v.edition,
       v.call_number_raw, v.classification_no, v.book_symbol, v.copy_number,
       (SELECT shelf_id FROM shelf WHERE shelf_code = v.expected_shelf),
       (SELECT shelf_id FROM shelf WHERE shelf_code = v.current_shelf),
       v.lifecycle_status, v.loan_status, v.last_loan_date::date, v.total_loan_count, v.book_width_cm,
       now(), now()
FROM (VALUES
  ('EM000001', '9780000000011', '컴퓨터 개론',           '박컴',   '한빛출판', 2021, '3판', '004 박64ㅋ',   '004',    '박64ㅋ',  'c.1', 'A-01', 'A-01', 'NORMAL',  'AVAILABLE', '2026-09-12', 14, 3.20),
  ('EM000002', '9780000000028', '자료구조와 알고리즘',   '최알고', '한빛출판', 2020, '2판', '005.1 최65ㅈ', '005.1',  '최65ㅈ',  'c.1', 'A-01', 'A-01', 'NORMAL',  'ON_LOAN',   '2026-11-02', 31, 3.80),
  ('EM000003', '9780000000035', '자료구조와 알고리즘',   '최알고', '한빛출판', 2020, '2판', '005.1 최65ㅈ', '005.1',  '최65ㅈ',  'c.2', 'A-01', 'A-01', 'NORMAL',  'AVAILABLE', '2026-10-05', 22, 3.80),
  ('EM000004', '9780000000042', '클린 소프트웨어 설계',  '정설계', '이지북스', 2019, NULL,  '005.12 정66ㅋ','005.12', '정66ㅋ',  'c.1', 'A-01', 'A-02', 'NORMAL',  'AVAILABLE', '2026-08-20',  9, 2.90),
  ('EM000005', '9780000000059', '데이터베이스 시스템',   '한디비', '정보문화', 2018, '5판', '005.74 한67ㄷ','005.74', '한67ㄷ',  'c.1', 'A-01', 'A-01', 'DAMAGED', 'AVAILABLE', '2026-05-30', 27, 4.50),
  ('EM000006', '9780000000066', '경제학 원론',           '윤경제', '미래사',   2017, '4판', '320 윤68ㄱ',   '320',    '윤68ㄱ',  'c.1', 'A-02', 'A-02', 'NORMAL',  'AVAILABLE', '2026-03-14',  6, 3.10),
  ('EM000007', '9780000000073', '사회과학 연구방법론',   '강사회', '미래사',   2016, NULL,  '300.7 강69ㅅ', '300.7',  '강69ㅅ',  'c.1', 'A-02', 'A-02', 'NORMAL',  'RESERVED',  '2026-10-28', 11, 2.60),
  ('EM000008', '9780000000080', '현대 심리학',           '조심리', '마음출판', 2022, '1판', '180 조70ㅎ',   '180',    '조70ㅎ',  'c.1', 'A-02', 'A-02', 'MISSING', 'LOST',      '2026-01-09',  4, 2.80),
  ('EM000009', '9780000000097', '일반 물리학',           '신물리', '과학사',   2019, '9판', '420 신71ㅇ',   '420',    '신71ㅇ',  'c.1', 'A-03', 'A-03', 'NORMAL',  'AVAILABLE', '2026-09-30', 18, 5.20),
  ('EM000010', '9780000000103', '선형대수학',           '오수학', '과학사',   2018, '3판', '512.5 오72ㅅ', '512.5',  '오72ㅅ',  'c.1', 'A-03', 'A-03', 'NORMAL',  'ON_LOAN',   '2026-11-05', 25, 3.40),
  ('EM000011', '9780000000110', '유기화학',             '임화학', '과학사',   2015, '7판', '572 임73ㅇ',   '572',    '임73ㅇ',  'c.1', 'A-03', 'A-03', 'NORMAL',  'AVAILABLE', '2026-06-17',  7, 4.80),
  ('EM000012', '9780000000127', '서양미술사',           '송미술', '예원',     2014, NULL,  '709 송74ㅅ',   '709',    '송74ㅅ',  'c.1', 'B-01', 'B-01', 'NORMAL',  'AVAILABLE', '2026-04-22',  5, 3.60),
  ('EM000013', '9780000000134', '음악의 이해',           '권음악', '예원',     2020, '2판', '760 권75ㅇ',   '760',    '권75ㅇ',  'c.1', 'B-01', 'B-01', 'NORMAL',  'AVAILABLE', NULL,          0, 2.40),
  ('EM000014', '9780000000141', '한국 현대문학 선집',    '문문학', '글빛',     2013, NULL,  '810.8 문76ㅎ', '810.8',  '문76ㅎ',  'c.1', 'B-02', 'B-02', 'NORMAL',  'ON_LOAN',   '2026-10-30', 33, 4.10),
  ('EM000015', '9780000000158', '영미시 입문',           '배영미', '글빛',     2012, NULL,  '841 배77ㅇ',   '841',    '배77ㅇ',  'c.1', 'B-02', 'B-01', 'NORMAL',  'AVAILABLE', '2026-07-08',  8, 2.20),
  ('EM000016', '9780000000165', '세계사 산책',           '홍역사', '이음',     2021, '1판', '909 홍78ㅅ',   '909',    '홍78ㅅ',  'c.1', 'B-02', NULL,   'DISPOSED','AVAILABLE', '2025-12-15', 12, 3.30)
) AS v(registration_no, isbn, title, author, publisher, published_year, edition,
       call_number_raw, classification_no, book_symbol, copy_number,
       expected_shelf, current_shelf, lifecycle_status, loan_status,
       last_loan_date, total_loan_count, book_width_cm)
ON CONFLICT (registration_no) DO NOTHING;

-- 도서 변경 이력 샘플 (/api/books/{id}/histories 확인용). 이미 있으면 다시 넣지 않는다.
INSERT INTO audit_log (actor_id, entity_type, entity_id, action, before_data, after_data, created_at)
SELECT (SELECT user_id FROM app_user WHERE email = 'librarian@example.com'),
       'BOOK', b.book_id, 'CREATE', NULL,
       jsonb_build_object('title', b.title, 'registrationNo', b.registration_no, 'lifecycleStatus', 'NORMAL', 'loanStatus', 'AVAILABLE'),
       timestamp '2026-03-01 10:00:00'
FROM book b
WHERE b.registration_no = 'EM000004'
  AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.entity_type = 'BOOK' AND a.entity_id = b.book_id AND a.action = 'CREATE');

INSERT INTO audit_log (actor_id, entity_type, entity_id, action, before_data, after_data, created_at)
SELECT (SELECT user_id FROM app_user WHERE email = 'worker@example.com'),
       'BOOK', b.book_id, 'UPDATE',
       jsonb_build_object('currentShelfCode', 'A-01'),
       jsonb_build_object('currentShelfCode', 'A-02'),
       timestamp '2026-10-14 15:30:00'
FROM book b
WHERE b.registration_no = 'EM000004'
  AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.entity_type = 'BOOK' AND a.entity_id = b.book_id AND a.action = 'UPDATE');

INSERT INTO audit_log (actor_id, entity_type, entity_id, action, before_data, after_data, created_at)
SELECT (SELECT user_id FROM app_user WHERE email = 'librarian@example.com'),
       'BOOK', b.book_id, 'STATUS_CHANGE',
       jsonb_build_object('lifecycleStatus', 'NORMAL', 'loanStatus', 'AVAILABLE'),
       jsonb_build_object('lifecycleStatus', 'MISSING', 'loanStatus', 'LOST'),
       timestamp '2026-09-02 09:10:00'
FROM book b
WHERE b.registration_no = 'EM000008'
  AND NOT EXISTS (SELECT 1 FROM audit_log a WHERE a.entity_type = 'BOOK' AND a.entity_id = b.book_id AND a.action = 'STATUS_CHANGE');
