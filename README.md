# Smart Library Backend

404-FOUND 팀의 스마트 도서관 백엔드 서버.

## Tech Stack
Java 17 · Spring Boot 4 · Spring Data JPA · Spring Security · PostgreSQL · Flyway · Gradle

## 시작하기
```bash
cp .env.example .env     # DB 정보 등 값 채우기
docker compose up -d      # 로컬 PostgreSQL + Redis 실행 (.env 의 DB_*, REDIS_* 값 사용)
./gradlew bootRun
```
스키마 변경은 `src/main/resources/db/migration` 의 Flyway 파일(`V{n}__설명.sql`)로만 한다.

`.env` 의 `JWT_SECRET` 은 32바이트 이상의 임의 문자열로 바꾼다 (`openssl rand -base64 48`). 비어 있거나 짧으면 앱이 기동하지 않는다.

로그인 세션과 Refresh Token 은 Redis 에 저장한다(TTL 로 자동 만료). Redis 가 꺼져 있으면 로그인·토큰 검증이 실패하므로 `docker compose up -d` 로 함께 띄워야 한다.

### 개발용 시드 데이터
`.env` 에 `spring.profiles.active=dev` 가 있으면 기동 시 샘플 서가·도서·계정이 들어간다 (`src/main/resources/db/seed/`).

| 계정 | 비밀번호 | 권한 |
| --- | --- | --- |
| `librarian@example.com` | `Password1234!` | 사서 (LIBRARIAN) |
| `worker@example.com` | `Password1234!` | 근로생 (STUDENT_WORKER) |

## 배포
EC2 + Docker Compose 배포 절차는 [docs/DEPLOY.md](docs/DEPLOY.md) 를 본다. 운영 환경변수 예시는 `.env.prod.example`.

## API
모든 응답은 `{ "success": true, "data": ... }`, 에러는 `{ "code", "message", "errors" }` 형식이다.
로그인 후 받은 `accessToken` 을 `Authorization: Bearer {token}` 으로 보낸다.

| Method | Endpoint | 권한 |
| --- | --- | --- |
| POST | `/api/auth/signup` · `/login` · `/refresh` | 공개 |
| POST | `/api/auth/logout` | 로그인 |
| GET · DELETE | `/api/users/me` | 로그인 |
| GET | `/api/shelves` · `/{id}` · `/{id}/books` · `/api/maps` | 로그인 |
| PUT | `/api/shelves/{id}` | 사서 |
| GET | `/api/books` · `/{id}` · `/{id}/histories` | 로그인 |
| POST | `/api/books` | 사서 |
| PATCH | `/api/books/{id}` | 로그인 |
| GET · POST | `/api/books/{id}/condition-reports` | 로그인 (근로생도 등록 가능) |
| GET | `/api/evaluation/settings` | 로그인 (근로생은 읽기 전용) |
| PUT | `/api/evaluation/settings` | 사서 |
| GET | `/api/scan-sessions/{id}/reviews` | 로그인 (검토 필요 관측 목록·진행 현황) |
| PUT · DELETE | `/api/scan-sessions/{id}/observations/{observationId}/review` | 사서 (검토 저장·취소) |
| POST | `/api/scan-sessions/{id}/finalize` | 사서 (검토 완료 후 회차 확정, BOOK 반영) |
| POST | `/api/scan-sessions/{id}/jev-evaluations` | 사서 (점검 확정된 회차의 도서를 JEV 에 평가시켜 결과 저장, 재호출 안전) |
| GET | `/api/scan-sessions/{id}/missing-candidates` | 로그인 (소재불명 후보 목록) |
| PUT | `/api/missing-candidates/{id}/review` | 로그인 (현장 확인 결과 기록) |

## 프로젝트 구조
```
src/main/java/com/__found/smart_library/
├── domain/    # 도메인별 패키지 (controller / service / repository / entity / dto)
└── global/    # 공통 (entity, config, exception, response)
```
- 엔티티는 `BaseEntity` 상속, 성공 응답은 `ApiResponse`, 예외는 `BusinessException` + `ErrorCode` 사용

---

## 브랜치 전략
`main` + 작업 브랜치 구성 (GitHub Flow).

| 브랜치 | 설명 |
| --- | --- |
| `main` | 항상 배포 가능한 안정 버전. 직접 push 금지, PR로만 머지 |
| `feat/{이슈번호}-{설명}` | 기능 개발 (예: `feat/12-book-search`) |
| `fix/{이슈번호}-{설명}` | 버그 수정 |
| `refactor/…`, `docs/…`, `chore/…` | 그 외 작업 |

규칙
- 작업 브랜치는 `main`에서 분기하고, 작업이 끝나면 `main`으로 PR
- PR은 **최소 1명 approve** 후 머지 (Squash and merge 권장)
- 머지된 작업 브랜치는 삭제

## 이슈 컨벤션
- 제목: `[타입] 작업 내용` (예: `[Feat] 도서 검색 API 구현`)
- 타입: `Feat` · `Fix` · `Refactor` · `Docs` · `Chore` · `Test`
- 본문: 작업 개요 / 상세 TODO(체크리스트) / 참고 사항
- 이슈 하나 = 브랜치 하나 = PR 하나를 기본으로 하고, PR에는 `closes #이슈번호`를 적는다.
- 담당자(Assignee)와 라벨을 지정한다.

## 커밋 메시지 컨벤션
```
타입: 제목 (50자 이내, 마침표 없음)

본문 (선택) - 무엇을, 왜 변경했는지
```

| 타입 | 설명 |
| --- | --- |
| `feat` | 새로운 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 코드 개선 |
| `docs` | 문서 수정 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 설정, 의존성 등 기타 |
| `style` | 포맷팅 등 코드 의미 없는 변경 |

예) `feat: 도서 검색 API 추가`, `fix: 대출 연장 시 NPE 수정`

## PR 컨벤션
- 제목은 커밋 컨벤션과 동일 (`feat: 도서 검색 API 추가`)
- `.github/pull_request_template.md` 양식에 맞춰 작성
- 리뷰는 되도록 하루 안에, 코멘트는 반영 후 resolve
