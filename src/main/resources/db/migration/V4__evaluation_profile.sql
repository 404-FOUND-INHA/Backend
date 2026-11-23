-- ============================================================
-- 평가 설정(가중치 + 폐기 검토 후보 기준 점수)
-- 사서가 설정을 저장할 때마다 새 profile_version 이 생기고, 이전 버전은 비활성화된다.
-- 평가 결과가 어떤 설정으로 계산됐는지 추적하기 위해 이전 버전은 지우지 않는다.
-- ============================================================
CREATE TABLE evaluation_profile (
  profile_version    VARCHAR(30)  NOT NULL,
  disposal_threshold DECIMAL(5,2) NOT NULL,
  is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
  configured_by      BIGINT       NOT NULL,
  created_at         TIMESTAMP    NOT NULL,
  PRIMARY KEY (profile_version),
  CONSTRAINT ck_profile_threshold CHECK (disposal_threshold >= 0 AND disposal_threshold <= 100),
  CONSTRAINT fk_profile_user FOREIGN KEY (configured_by) REFERENCES app_user (user_id)
);
COMMENT ON TABLE evaluation_profile IS '평가 설정 버전(후보 기준 점수). 가중치는 evaluation_criterion_weight 가 같은 profile_version 으로 가진다';
COMMENT ON COLUMN evaluation_profile.profile_version IS '설정 버전(v1, v2 ...)';
COMMENT ON COLUMN evaluation_profile.disposal_threshold IS '폐기 검토 후보 기준 점수(0~100)';
COMMENT ON COLUMN evaluation_profile.is_active IS '현재 사용 중인 버전 여부';
COMMENT ON COLUMN evaluation_profile.configured_by IS '설정한 사서';
COMMENT ON COLUMN evaluation_profile.created_at IS '설정 시각';

-- 활성 버전은 항상 최대 1개
CREATE UNIQUE INDEX uk_evaluation_profile_active ON evaluation_profile ((TRUE)) WHERE is_active;

ALTER TABLE evaluation_criterion_weight
  ADD CONSTRAINT fk_weight_profile FOREIGN KEY (profile_version) REFERENCES evaluation_profile (profile_version);
