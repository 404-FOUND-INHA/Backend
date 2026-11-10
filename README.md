# Smart Library Backend

404-FOUND 팀의 스마트 도서관 백엔드 서버.

## Tech Stack
Java 17 · Spring Boot 4 · Spring Data JPA · Spring Security · PostgreSQL · Flyway · Gradle

## 시작하기
```bash
cp .env.example .env     # DB 정보 등 값 채우기
./gradlew bootRun
```
스키마 변경은 `src/main/resources/db/migration` 의 Flyway 파일(`V{n}__설명.sql`)로만 한다.

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
