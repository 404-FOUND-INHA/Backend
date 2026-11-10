-- 전체 기능 반영 데이터 모델
-- 현재 MVP 범위 반영본
-- ERD Cloud: 상단 메뉴 Import > SQL(DDL) > MySQL 선택 후 붙여넣기

-- ============================================================
-- 사용자·인증
-- ============================================================
CREATE TABLE app_user (
  user_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '사용자 식별자',
  email          VARCHAR(255) NOT NULL COMMENT '로그인 이메일',
  password_hash  VARCHAR(255) NOT NULL COMMENT '암호화된 비밀번호',
  name           VARCHAR(50)  NOT NULL COMMENT '사용자 이름',
  role           VARCHAR(30)  NOT NULL COMMENT 'LIBRARIAN / STUDENT_WORKER',
  status         VARCHAR(30)  NOT NULL COMMENT '계정 상태',
  last_login_at  DATETIME     NULL COMMENT '마지막 로그인 시각',
  withdrawn_at   DATETIME     NULL COMMENT '탈퇴 시각',
  created_at     DATETIME     NOT NULL COMMENT '생성 시각',
  updated_at     DATETIME     NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_app_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='서비스 사용자(사서·근로생)';

CREATE TABLE auth_session (
  auth_session_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '세션 식별자',
  user_id            BIGINT       NOT NULL COMMENT '로그인 사용자',
  refresh_token_hash VARCHAR(255) NOT NULL COMMENT 'Refresh Token 해시',
  ip_address         VARCHAR(45)  NULL COMMENT '접속 IP',
  user_agent         VARCHAR(500) NULL COMMENT '브라우저·기기 정보',
  issued_at          DATETIME     NOT NULL COMMENT '발급 시각',
  expires_at         DATETIME     NOT NULL COMMENT '만료 시각',
  revoked_at         DATETIME     NULL COMMENT '폐기 시각',
  PRIMARY KEY (auth_session_id),
  CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='인증 세션';

-- ============================================================
-- 서가·도서
-- ============================================================
CREATE TABLE shelf (
  shelf_id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '서가 식별자',
  shelf_code        VARCHAR(50)   NOT NULL COMMENT '화면 표시 서가 코드 / Vision과의 공유 식별자',
  zone              VARCHAR(30)   NULL COMMENT '열람실·구역',
  floor_no          INT           NULL COMMENT '건물 층',
  shelf_no          INT           NULL COMMENT '구역 내 서가 번호',
  level_no          INT           NULL COMMENT '서가 내부 단 번호',
  call_number_start VARCHAR(150)  NULL COMMENT '배치 청구기호 시작 범위',
  call_number_end   VARCHAR(150)  NULL COMMENT '배치 청구기호 끝 범위',
  marker_id         VARCHAR(50)   NULL COMMENT 'Vision용 서가 마커',
  map_x             DECIMAL(10,2) NULL COMMENT '지도 X 좌표',
  map_y             DECIMAL(10,2) NULL COMMENT '지도 Y 좌표',
  map_z             DECIMAL(10,2) NULL COMMENT '지도 Z 좌표',
  width_cm          DECIMAL(8,2)  NULL COMMENT '서가 가로',
  height_cm         DECIMAL(8,2)  NULL COMMENT '서가 높이',
  depth_cm          DECIMAL(8,2)  NULL COMMENT '서가 깊이',
  rotation_degree   DECIMAL(5,2)  NULL COMMENT '지도상 방향',
  display_order     INT           NULL COMMENT '화면 표시 순서',
  is_active         BOOLEAN       NOT NULL DEFAULT TRUE COMMENT '사용 중인 서가 여부',
  created_at        DATETIME      NOT NULL COMMENT '생성 시각',
  updated_at        DATETIME      NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (shelf_id),
  UNIQUE KEY uk_shelf_code (shelf_code),
  UNIQUE KEY uk_shelf_marker (marker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='서가';

CREATE TABLE book (
  book_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '도서 식별자',
  registration_no   VARCHAR(50)  NOT NULL COMMENT '도서 등록번호',
  isbn              VARCHAR(13)  NULL COMMENT 'ISBN',
  title             VARCHAR(255) NOT NULL COMMENT '서명',
  author            VARCHAR(255) NULL COMMENT '저자',
  publisher         VARCHAR(255) NULL COMMENT '출판사',
  published_year    INT          NULL COMMENT '출판 연도',
  edition           VARCHAR(50)  NULL COMMENT '판·개정 정보',
  call_number_raw   VARCHAR(150) NOT NULL COMMENT '전체 청구기호 원문',
  classification_no VARCHAR(50)  NOT NULL COMMENT '분류기호',
  book_symbol       VARCHAR(50)  NOT NULL COMMENT '도서기호',
  copy_number       VARCHAR(30)  NULL COMMENT '복본기호',
  expected_shelf_id BIGINT       NULL COMMENT '정상 배치 서가',
  current_shelf_id  BIGINT       NULL COMMENT '마지막 확인 서가',
  lifecycle_status  VARCHAR(30)  NOT NULL COMMENT '정상·소재불명·훼손·제적',
  loan_status       VARCHAR(30)  NOT NULL COMMENT '대출 가능·대출 중·예약·분실',
  last_loan_date    DATE         NULL COMMENT '마지막 대출일',
  total_loan_count  INT          NOT NULL DEFAULT 0 COMMENT '누적 대출 횟수',
  book_width_cm     DECIMAL(6,2) NULL COMMENT '책 두께',
  imported_at       DATETIME     NULL COMMENT '외부 DB에서 가져온 시각',
  created_at        DATETIME     NOT NULL COMMENT '생성 시각',
  updated_at        DATETIME     NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (book_id),
  UNIQUE KEY uk_book_registration_no (registration_no),
  CONSTRAINT fk_book_expected_shelf FOREIGN KEY (expected_shelf_id) REFERENCES shelf (shelf_id),
  CONSTRAINT fk_book_current_shelf  FOREIGN KEY (current_shelf_id)  REFERENCES shelf (shelf_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='개별 도서';

-- ============================================================
-- 대출·반납 (borrower / loan_event / loan_suspension 삭제)
-- ============================================================
CREATE TABLE loan (
  loan_id     BIGINT      NOT NULL AUTO_INCREMENT COMMENT '대출 식별자',
  book_id     BIGINT      NOT NULL COMMENT '대출 도서',
  loaned_at   DATETIME    NOT NULL COMMENT '대출 시각',
  returned_at DATETIME    NULL COMMENT '실제 반납 시각',
  status      VARCHAR(30) NOT NULL COMMENT '대출 중·반납·분실',
  created_at  DATETIME    NOT NULL COMMENT '생성 시각',
  updated_at  DATETIME    NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (loan_id),
  CONSTRAINT fk_loan_book FOREIGN KEY (book_id) REFERENCES book (book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='대출 이력';

-- ============================================================
-- 장서점검
-- ============================================================
CREATE TABLE scan_session (
  scan_session_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '점검 회차 식별자',
  session_name              VARCHAR(100) NOT NULL COMMENT '회차명',
  planned_start_date        DATE         NULL COMMENT '예정 시작일',
  planned_end_date          DATE         NULL COMMENT '예정 종료일',
  started_at                DATETIME     NULL COMMENT '실제 시작 시각',
  ended_at                  DATETIME     NULL COMMENT '실제 종료 시각',
  finalized_at              DATETIME     NULL COMMENT '결과 확정 시각',
  status                    VARCHAR(30)  NOT NULL COMMENT '초안·진행·검토·완료',
  target_area               VARCHAR(100) NULL COMMENT '점검 대상 구역',
  total_expected_books      INT          NULL COMMENT '점검 대상 도서 수',
  total_detected_books      INT          NULL COMMENT 'Vision이 발견한 도서 수',
  received_observation_count INT         NULL COMMENT '수신한 인식 결과 수',
  created_by                BIGINT       NOT NULL COMMENT '회차 생성자',
  finalized_by              BIGINT       NULL COMMENT '회차 확정자',
  created_at                DATETIME     NOT NULL COMMENT '생성 시각',
  updated_at                DATETIME     NOT NULL COMMENT '수정 시각',
  PRIMARY KEY (scan_session_id),
  CONSTRAINT fk_scan_session_created_by   FOREIGN KEY (created_by)   REFERENCES app_user (user_id),
  CONSTRAINT fk_scan_session_finalized_by FOREIGN KEY (finalized_by) REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='장서점검 회차';

CREATE TABLE scan_session_assignee (
  scan_session_id BIGINT   NOT NULL COMMENT '점검 회차',
  user_id         BIGINT   NOT NULL COMMENT '담당 사용자',
  assigned_at     DATETIME NOT NULL COMMENT '배정 시각',
  PRIMARY KEY (scan_session_id, user_id),
  CONSTRAINT fk_assignee_session FOREIGN KEY (scan_session_id) REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_assignee_user    FOREIGN KEY (user_id)         REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='점검 회차-담당자';

CREATE TABLE scan_session_shelf (
  scan_session_id    BIGINT      NOT NULL COMMENT '점검 회차',
  shelf_id           BIGINT      NOT NULL COMMENT '대상 서가',
  scan_status        VARCHAR(30) NOT NULL COMMENT '대기·촬영·결과 수신·검토',
  expected_book_count INT        NULL COMMENT '예상 도서 수',
  detected_book_count INT        NULL COMMENT '검출 도서 수',
  scanned_at         DATETIME    NULL COMMENT '촬영 시각',
  PRIMARY KEY (scan_session_id, shelf_id),
  CONSTRAINT fk_sss_session FOREIGN KEY (scan_session_id) REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_sss_shelf   FOREIGN KEY (shelf_id)        REFERENCES shelf (shelf_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='점검 회차-대상 서가';

CREATE TABLE book_observation (
  observation_id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '인식 결과 식별자',
  scan_session_id            BIGINT        NOT NULL COMMENT '점검 회차',
  book_id                    BIGINT        NULL COMMENT 'Backend가 자동 매칭한 도서',
  detected_shelf_id          BIGINT        NULL COMMENT 'Vision shelfCode를 Backend가 변환한 서가',
  detected_label_raw         VARCHAR(200)  NULL COMMENT 'OCR 라벨 원문',
  detected_classification_no VARCHAR(50)   NULL COMMENT 'Backend가 파싱한 분류기호',
  detected_book_symbol       VARCHAR(50)   NULL COMMENT 'Backend가 파싱한 도서기호',
  detected_copy_number       VARCHAR(30)   NULL COMMENT 'Backend가 파싱한 복본기호',
  detected_title             VARCHAR(255)  NULL COMMENT 'Vision이 인식한 서명',
  detected_order             INT           NOT NULL COMMENT '서가 왼쪽부터의 인식 순서',
  x_position_cm              DECIMAL(8,2)  NULL COMMENT '서가 내 가로 위치',
  match_confidence           DECIMAL(5,4)  NULL COMMENT 'Backend DB 매칭 신뢰도',
  analysis_status            VARCHAR(30)   NOT NULL COMMENT 'NORMAL / MISPLACED / REVIEW_REQUIRED',
  status_reason              VARCHAR(255)  NULL COMMENT 'OCR_FAILED / NO_MATCH / AMBIGUOUS_MATCH / WRONG_SHELF 등',
  detected_at                DATETIME      NULL COMMENT 'Vision 촬영 / 인식 시각',
  received_at                DATETIME      NOT NULL COMMENT '서버 수신 시각',
  PRIMARY KEY (observation_id),
  CONSTRAINT fk_obs_session FOREIGN KEY (scan_session_id)   REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_obs_book    FOREIGN KEY (book_id)           REFERENCES book (book_id),
  CONSTRAINT fk_obs_shelf   FOREIGN KEY (detected_shelf_id) REFERENCES shelf (shelf_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Vision OCR 결과 및 Backend 분석 결과';

CREATE TABLE observation_match_candidate (
  observation_id BIGINT       NOT NULL COMMENT '대상 인식 결과',
  candidate_rank INT          NOT NULL COMMENT '후보 순위(1~3)',
  book_id        BIGINT       NOT NULL COMMENT '후보 도서',
  match_reason   VARCHAR(255) NULL COMMENT '후보 선정 이유',
  PRIMARY KEY (observation_id, candidate_rank),
  CONSTRAINT fk_omc_observation FOREIGN KEY (observation_id) REFERENCES book_observation (observation_id),
  CONSTRAINT fk_omc_book        FOREIGN KEY (book_id)        REFERENCES book (book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='검토 필요 인식 결과의 도서 매칭 후보';

CREATE TABLE review (
  review_id      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '검토 식별자',
  observation_id BIGINT      NOT NULL COMMENT '검토 대상 인식 결과',
  final_book_id  BIGINT      NULL COMMENT '최종 선택한 도서',
  final_status   VARCHAR(30) NOT NULL COMMENT '최종 상태(자동 계산)',
  review_action  VARCHAR(30) NOT NULL COMMENT '사서가 취한 최종 조치',
  reviewer_id    BIGINT      NOT NULL COMMENT '검토자',
  comment        TEXT        NULL COMMENT '검토 의견',
  reviewed_at    DATETIME    NOT NULL COMMENT '검토 시각',
  PRIMARY KEY (review_id),
  UNIQUE KEY uk_review_observation (observation_id),
  CONSTRAINT fk_review_observation FOREIGN KEY (observation_id) REFERENCES book_observation (observation_id),
  CONSTRAINT fk_review_book        FOREIGN KEY (final_book_id)  REFERENCES book (book_id),
  CONSTRAINT fk_review_reviewer    FOREIGN KEY (reviewer_id)    REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사서의 장서점검 결과 검토';

CREATE TABLE missing_candidate (
  missing_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '후보 식별자',
  scan_session_id           BIGINT       NOT NULL COMMENT '점검 회차',
  book_id                   BIGINT       NOT NULL COMMENT '대상 도서',
  expected_shelf_id         BIGINT       NULL COMMENT '원래 있어야 하는 서가',
  consecutive_missing_count INT          NOT NULL DEFAULT 1 COMMENT '연속 미발견 횟수',
  status                    VARCHAR(30)  NOT NULL COMMENT '확인 전·찾음·못 찾음·분실 후보·해결',
  reviewed_by               BIGINT       NULL COMMENT '현장 확인자',
  reviewed_at               DATETIME     NULL COMMENT '확인 시각',
  note                      TEXT         NULL COMMENT '확인 메모',
  created_at                DATETIME     NOT NULL COMMENT '후보 생성 시각',
  PRIMARY KEY (missing_id),
  UNIQUE KEY uk_missing_session_book (scan_session_id, book_id),
  CONSTRAINT fk_missing_session  FOREIGN KEY (scan_session_id)   REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_missing_book     FOREIGN KEY (book_id)           REFERENCES book (book_id),
  CONSTRAINT fk_missing_shelf    FOREIGN KEY (expected_shelf_id) REFERENCES shelf (shelf_id),
  CONSTRAINT fk_missing_reviewer FOREIGN KEY (reviewed_by)       REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='소재불명 후보';

-- ============================================================
-- 서가 작업·오손
-- ============================================================
CREATE TABLE work_task (
  task_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '작업 식별자',
  scan_session_id BIGINT       NULL COMMENT '관련 점검 회차',
  shelf_id        BIGINT       NULL COMMENT '대상 서가',
  book_id         BIGINT       NULL COMMENT '대상 도서',
  task_type       VARCHAR(30)  NOT NULL COMMENT '재배가·소재 확인·OCR 검토·오손 확인',
  priority        INT          NULL COMMENT '우선순위',
  status          VARCHAR(30)  NOT NULL COMMENT '대기·진행·완료·취소',
  assigned_to     BIGINT       NULL COMMENT '담당자',
  display_order   INT          NULL COMMENT '서가 기준 작업 순서',
  instruction     VARCHAR(500) NULL COMMENT '작업 지시',
  result_note     TEXT         NULL COMMENT '처리 결과',
  created_at      DATETIME     NOT NULL COMMENT '생성 시각',
  started_at      DATETIME     NULL COMMENT '시작 시각',
  completed_at    DATETIME     NULL COMMENT '완료 시각',
  PRIMARY KEY (task_id),
  CONSTRAINT fk_task_session FOREIGN KEY (scan_session_id) REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_task_shelf   FOREIGN KEY (shelf_id)        REFERENCES shelf (shelf_id),
  CONSTRAINT fk_task_book    FOREIGN KEY (book_id)         REFERENCES book (book_id),
  CONSTRAINT fk_task_user    FOREIGN KEY (assigned_to)     REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='현장 작업 목록';

CREATE TABLE book_condition_report (
  condition_report_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '상태 기록 식별자',
  book_id             BIGINT       NOT NULL COMMENT '대상 도서',
  scan_session_id     BIGINT       NULL COMMENT '관련 점검 회차',
  reporter_id         BIGINT       NOT NULL COMMENT '기록한 사용자',
  condition_type      VARCHAR(30)  NOT NULL COMMENT '오염·찢김·침수·제본 파손',
  severity            VARCHAR(20)  NULL COMMENT '심각도',
  note                TEXT         NULL COMMENT '상세 내용',
  image_url           VARCHAR(500) NULL COMMENT '증빙 이미지(폰 직접 촬영)',
  reported_at         DATETIME     NOT NULL COMMENT '기록 시각',
  PRIMARY KEY (condition_report_id),
  CONSTRAINT fk_cond_book     FOREIGN KEY (book_id)         REFERENCES book (book_id),
  CONSTRAINT fk_cond_session  FOREIGN KEY (scan_session_id) REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_cond_reporter FOREIGN KEY (reporter_id)     REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='도서 오손·파손 기록';

-- ============================================================
-- JEV 평가 (jev_model 삭제 → model_version 필드로 대체)
-- ============================================================
CREATE TABLE evaluation_criterion_weight (
  criterion_weight_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '가중치 설정 식별자',
  profile_version     VARCHAR(30)  NOT NULL COMMENT '가중치 설정 버전',
  criterion_type      VARCHAR(30)  NOT NULL COMMENT '타당성·희소성·이용도·오손도',
  weight              DECIMAL(5,4) NOT NULL COMMENT '기준 가중치',
  is_active           BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '현재 사용 여부',
  configured_by       BIGINT       NOT NULL COMMENT '설정한 사서',
  created_at          DATETIME     NOT NULL COMMENT '설정 시각',
  PRIMARY KEY (criterion_weight_id),
  UNIQUE KEY uk_weight_profile_criterion (profile_version, criterion_type),
  CONSTRAINT fk_weight_user FOREIGN KEY (configured_by) REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='평가 축별 가중치';

CREATE TABLE book_evaluation (
  evaluation_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '평가 식별자',
  book_id           BIGINT       NOT NULL COMMENT '평가 대상 도서',
  scan_session_id   BIGINT       NULL COMMENT '관련 점검 회차',
  observation_id    BIGINT       NULL COMMENT '평가에 연결된 인식 결과',
  evaluation_type   VARCHAR(20)  NOT NULL COMMENT 'JEV / BACKEND',
  model_version     VARCHAR(30)  NULL COMMENT 'JEV 평가 모델 버전',
  final_score       DECIMAL(5,2) NULL COMMENT '평가 최종 점수',
  candidate_status  VARCHAR(30)  NOT NULL COMMENT '보존 / 폐기 검토 후보 등 평가 결과',
  evaluation_reason TEXT         NULL COMMENT 'Backend가 생성한 후보 선정 종합 근거',
  evaluated_at      DATETIME     NOT NULL COMMENT '평가 시각',
  PRIMARY KEY (evaluation_id),
  CONSTRAINT fk_eval_book    FOREIGN KEY (book_id)         REFERENCES book (book_id),
  CONSTRAINT fk_eval_session FOREIGN KEY (scan_session_id) REFERENCES scan_session (scan_session_id),
  CONSTRAINT fk_eval_obs     FOREIGN KEY (observation_id)  REFERENCES book_observation (observation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='도서 평가 결과(JEV 원본 / Backend 종합)';

CREATE TABLE evaluation_criterion_result (
  criterion_result_id   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '세부 결과 식별자',
  evaluation_id         BIGINT       NOT NULL COMMENT '상위 종합 평가',
  criterion_type        VARCHAR(30)  NOT NULL COMMENT '평가 축 종류',
  evaluator_type        VARCHAR(20)  NOT NULL COMMENT 'JEV / BACKEND',
  model_version         VARCHAR(30)  NULL COMMENT 'JEV 계산 시 모델·평가 로직 버전',
  raw_score             DECIMAL(5,2) NULL COMMENT '원점수',
  normalized_score      DECIMAL(5,2) NULL COMMENT '정규화 점수',
  confidence             DECIMAL(5,4) NULL COMMENT 'JEV 평가 신뢰도',
  selected_grade        INT          NULL COMMENT '선택된 등급',
  probability_distribution JSON      NULL COMMENT '등급별 확률 분포',
  PRIMARY KEY (criterion_result_id),
  CONSTRAINT fk_ecr_evaluation FOREIGN KEY (evaluation_id) REFERENCES book_evaluation (evaluation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='평가 축별 세부 결과';


-- ============================================================
-- 감사 로그
-- ============================================================
CREATE TABLE audit_log (
  audit_log_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT '이력 식별자',
  actor_id     BIGINT      NULL COMMENT '변경한 사용자',
  entity_type  VARCHAR(50) NOT NULL COMMENT '변경 대상 종류',
  entity_id    BIGINT      NOT NULL COMMENT '변경 대상 식별자',
  action       VARCHAR(30) NOT NULL COMMENT '생성·수정·삭제·상태 변경',
  before_data  JSON        NULL COMMENT '변경 전 데이터',
  after_data   JSON        NULL COMMENT '변경 후 데이터',
  created_at   DATETIME    NOT NULL COMMENT '변경 시각',
  PRIMARY KEY (audit_log_id),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES app_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주요 데이터 변경 이력';

