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

-- 이미 가중치 행이 있는 DB(수동 입력 등)에서도 FK 를 걸 수 있도록 부모 행을 먼저 만든다. 가중치 행은 지우지 않는다.
-- 빈 DB 에서는 아래 두 문장이 아무 행도 만들지 않는다.
-- 기준 점수는 기록이 없으므로 기본값(50)으로 채우고, 설정자·시각은 가중치 행에서 가져온다.
INSERT INTO evaluation_profile (profile_version, disposal_threshold, is_active, configured_by, created_at)
SELECT w.profile_version, 50.00, FALSE, MIN(w.configured_by), MIN(w.created_at)
FROM evaluation_criterion_weight w
GROUP BY w.profile_version;

-- 활성 버전은 최대 1개: 활성 가중치를 가진 버전 중 가장 최근에 만든 것만 활성으로 한다.
UPDATE evaluation_profile
SET is_active = TRUE
WHERE profile_version = (
  SELECT w.profile_version
  FROM evaluation_criterion_weight w
  WHERE w.is_active
  GROUP BY w.profile_version
  ORDER BY MAX(w.created_at) DESC, w.profile_version DESC
  LIMIT 1
);

-- 가중치 행의 활성 여부를 버전의 활성 여부와 맞춘다(활성 버전의 행만 활성).
UPDATE evaluation_criterion_weight w
SET is_active = p.is_active
FROM evaluation_profile p
WHERE p.profile_version = w.profile_version;

ALTER TABLE evaluation_criterion_weight
  ADD CONSTRAINT fk_weight_profile FOREIGN KEY (profile_version) REFERENCES evaluation_profile (profile_version);
