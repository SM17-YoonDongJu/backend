# Domain Glossary — 손해사정 도메인

> **출처:** Notion — API 명세서(개별 페이지 20개) + 기능리스트(개별 페이지 20개)  
> **규칙:** 이 파일의 모든 항목은 위 Notion 페이지 출처로만 작성한다. 임의 해석·추측 금지.  
> **최종 동기화:** 2026-08-16

---

## 1. 서비스 개요

보험금 지급 결과에 의문이 있는 사용자를 대상으로:
1. **AI 리포트** — 적용 가능 보장·누락 특약·분쟁 포인트 분석 (AI 파이프라인은 FastAPI 담당)
   - 진입점은 Spring Boot: 사고 상황 입력 수신 + 진단서 S3 업로드 + OCR 트리거 SQS **producer** 발행. 이 메시지를 FastAPI(consumer)가 소비해 OCR·AI 리포트 생성을 수행한다.
2. **손해사정사 연결** — AI 초안을 검수한 사정사 중 사용자가 직접 선택해 매칭 (Spring Boot 담당)

> 보험업법 §189: 협상·합의 대리는 제공하지 않는다. 정보 제공 및 손해사정사 연결만 수행.

---

## 2. 핵심 행위자 (Role)

| 역할 | ERD Enum 값 | API `userType` 값 | 설명 | 접근 가능 기능 |
|------|------------|------------------|------|--------------|
| 일반 사용자 | `USER` | `insured_person` | 보험금 검토가 필요한 보험 계약자 | 리포트 생성 요청, 검수 리포트 목록 조회, 매칭 요청 |
| 자격 사정사 | `CERTIFICATED_ADJUSTER` | `adjuster` | 금융위원회 등록 손해사정사, 운영팀 활성화 완료 | 케이스 채택, 검수·등록, 심층 분석 리포트 열람 |
| 미자격 사정사 | `UNCERTIFICATED_ADJUSTER` | `adjuster` | 신청 후 미활성화 상태 | 로그인 가능, 채택 API 호출 시 403 |
| 관리자 | `ADMIN` | — | 운영팀 | 전체 관리, 사정사 계정 활성화, 구독 관리 |

> **`userType` vs Role 구분:** 회원가입 API(`POST /auth/register`) 요청 시 `userType`으로 `insured_person` 또는 `adjuster`를 전달한다. 이 값이 서버 내부 Role(`USER` / `CERTIFICATED_ADJUSTER` 등) 매핑의 출발점이다.  
> 보험업법 §186: 금융위 등록 자격자만 활성화 허용. 미활성화 사정사는 로그인은 되나 케이스 채택 불가(403).

---

## 3. 리포트 상태머신 (REPORTS.status)

```
[AI 초안 생성 완료]
        ↓
AWAITING_INSPECTION  ← 검수 대기. 사정사 채택 가능 목록에 노출됨.
        ↓  (1건 이상 검수 제안(REPORT_REVIEWS) 등록 완료)
AWAITING_ADOPTION    ← 사용자 선택 대기. 복수 검수 제안 비교 가능.
        ↓  (사용자가 제안을 선택해 상담 시작 → ChatRoom 개설)
COUNSELING           ← 채팅 중. WebSocket 채널 개설됨. 여기서 사용자가 최종 결정을 내린다.
        ├─ (사용자가 제안 수락 → 담당 사정사 확정) → CLOSED
        └─ (사용자가 상담 거절) → AWAITING_ADOPTION (다른 제안을 다시 선택 가능)
CLOSED               ← 상담 종료. 사용자가 제안을 **수락**해 담당 사정사가 확정된 종료 상태.
                       (사정사 최종 리포트 등록이 아니라 사용자 수락이 트리거다.)

NOT_SELECTED         ← 미채택. 접수 후 1주일 내 상담 완료(CLOSED)되지 못했거나 검수를 하나도
                       받지 못한 리포트가 스케줄러 스윕(ReportNotSelectionSweeper)으로 도달하는 상태.
                       AWAITING_INSPECTION·AWAITING_ADOPTION에서만 진입. **종료 상태 아님** —
                       신규 사정사 검수는 차단되지만, 이후 상담이 잡히면 COUNSELING으로 재개된다
                       (NOT_SELECTED → COUNSELING 허용, CLOSED 직행은 없음).

BLOCKED              ← AI 입력 가드레일 차단(보험·법률 외 주제, PII 복호화 실패 등). 위 상태 전이표에
                       없는 별도 진입점이다 — AI 워커가 OCR·초안 생성 이전에 파이프라인을 끊고
                       reports.status를 원시 SQL로 직접 'BLOCKED'로 세팅한다(Backend 도메인 메서드를
                       거치지 않음). **종료 상태**이며 `Report.ALLOWED_TRANSITIONS`엔 자기 자신으로만
                       존재해 어떤 리뷰/채택 흐름으로도 나가거나 들어올 수 없다.
> 상태 전이 허용표(`Report.applyReviewTransition`): AWAITING_INSPECTION→{AWAITING_ADOPTION, NOT_SELECTED}, AWAITING_ADOPTION→{COUNSELING, NOT_SELECTED}, COUNSELING→{CLOSED, AWAITING_ADOPTION}, NOT_SELECTED→{COUNSELING}, CLOSED→(종료), BLOCKED→(종료, AI 워커가 직접 세팅), NEEDS_REUPLOAD→(종료, AI 워커가 직접 세팅).

### 상태별 사용자 표시 문자열 (API 응답 `status` 필드)

| DB/도메인 코드 | 사용자 표시 문자열 | 사정사 채택 가능 | 사용자 리포트 열람 | 제안 선택(상담 시작) 가능 |
|--------------|-----------------|----------------|------------------|------------------------|
| (생성 중) | `생성 중` | — | — | — |
| `AWAITING_INSPECTION` | `채택 대기중` | ✅ | ❌ (AI 초안 직접 접근 차단) | ❌ |
| `AWAITING_ADOPTION` | `채택 대기중` | ✅ (계속 채택 가능) | ✅ (검수된 제안만) | ✅ |
| `COUNSELING` | `상담 중` | — | ✅ | 결정 단계(수락/거절) |
| `CLOSED` | `완료` | — | ✅ | ❌ |
| `NOT_SELECTED` | (표시 문자열 코드 미확정) | ❌ (신규 검수 차단) | ✅ | 재개 시 COUNSELING 가능 |
| `BLOCKED` | `BLOCKED`(그대로 노출, 별도 매핑 없음) | ❌ (검수 대상 자체가 아님) | ✅ | ❌ |
> 리포트 `status` 필터·응답 값 표기: `생성 중` / `채택 대기중` / `상담 중` / `완료`. `NOT_SELECTED`(미채택)·`BLOCKED`(가드레일 차단)·`NEEDS_REUPLOAD`(OCR 품질 미달)의 사용자 표시 문자열은 `ReportResponseSupport.customerStatus()`가 `CLOSED`만 `"MATCHED"`로 바꾸고 나머지는 enum 이름을 그대로 내리는 방식이라, 셋 다 코드값 그대로 노출된다(전용 한글 문구는 코드에 아직 정의되지 않음).

### 3-1. 분석 처리 상태 (AnalysisState — REPORTS.status와 별도 축)

리포트 생명주기(`REPORTS.status`)와 별개로, "OCR·AI 분석 파이프라인이 지금 어느 단계인가"를 나타내는 축이 있다. **DB 컬럼이 아니라 조회 시점마다 파생하는 값**(`ReportAnalysis.of`)이며, 저장하면 안 된다 — AI 워커가 실패를 회복해 저널 행을 지웠을 때 그 회복 전이를 놓쳐 사용자에게 영원히 실패로 남기 때문이다.

- **판정 우선순위(작을수록 우선):**
  1. `REPORTS.status == BLOCKED` → `BLOCKED`(가드레일이 OCR 이전에 끊겨 아래 저널에 흔적이 없다. `reports.status`에서 직접 판정)
  2. `REPORTS.status == NEEDS_REUPLOAD` → `NEEDS_REUPLOAD`(OCR 품질 미달 — "실패"가 아니라 "품질 판정"이라 저널에 흔적이 없다. `reports.status`에서 직접 판정, AI 초안 존재 여부보다 우선해 "초안은 있는데 재업로드가 필요하다"는 모순 상태를 막는다)
  3. AI 초안 생성됨(`applicable_guarantees != null`) → `COMPLETED`(확정 실패 행이 남아 있어도 성공이 이긴다)
  4. `ai.ocr_job_failures`에 `terminal=true` 행 존재 → `FAILED`
  5. 그 외 → `PROCESSING`
- **`ai.ocr_job_failures`** — AI 워커가 OCR 처리 실패를 기록하는 계약 테이블. **AI 워커 소유**(Flyway로 만들지 않는다), Backend는 SELECT만 한다. 권한(GRANT)이 아직 없어도 앱 부팅이 막히지 않도록 `@Subselect` 읽기 전용 엔티티(`OcrJobFailureView`)로 매핑한다(§ddd-tactical 스킬 참고). `BLOCKED`·`NEEDS_REUPLOAD` 리포트에 저널 행이 남아 있어도(가정 위반 시) 이 우선순위 배치 덕분에 `isFailed()`가 `false`가 되어 실패 스윕과 중복 알림이 나지 않는다 — 다만 배치 슬롯은 계속 소모하므로 `OcrJobFailureViewRepositoryImpl`의 조회 조건에서 두 종료 상태를 제외한다.
- **실패 사유(`AnalysisFailureReason`)**: `MASKING_RESIDUAL`(마스킹 잔류, 재업로드해도 동일 실패)/`SCHEMA_INVALID`(계약 위반)/`OCR_ERROR`/`UNKNOWN`/`UNREADABLE_FILE`(유일하게 재업로드로 해결 가능). 문서 여러 건이 다른 사유로 실패하면 이 순서로 대표 사유를 고른다 — `UNREADABLE_FILE`이 가장 낮은 우선순위라 다른 사유가 하나라도 섞이면 절대 대표가 되지 않는다(잘못된 재업로드 안내 방지). `NEEDS_REUPLOAD`는 `ai.ocr_results.ocr_quality`(다른 테이블·다른 컬럼)의 판정이라 이 ACL enum에 편입하지 않는다 — `NEEDS_REUPLOAD` 상태의 `failure_reason`은 항상 `null`.
- **API**: `GET /reports/{reportId}/analysis-status`(폴링용 전용 엔드포인트, **소유자 전용** — 실패 문서 파일명이 실리는 유일한 응답이라 사정사에겐 안 연다) + 목록(`GET /reports`)·상세(`GET /reports/{reportId}`)에 평면 3필드(`analysis_state`/`analysis_failure_reason`/`analysis_failure_message`)로도 노출. `NEEDS_REUPLOAD`의 `failed_documents`는 현재 빈 배열 고정 — 문서 단위 상세(`ai.ocr_results` 연동)는 후속 이슈로 분리됐다(GRANT 미적용).
- **알림**: 확정 실패·BLOCKED·NEEDS_REUPLOAD 각각 별도 스케줄러 스윕(`AnalysisFailureNotificationSweeper`/`BlockedReportNotificationSweeper`/`NeedsReuploadNotificationSweeper`)이 감지해 인앱 알림+FCM 푸시. 조회 시점 주입이 아니라 스윕인 이유는 앱을 닫아둔 사용자에게도 푸시로 알려야 하기 때문. 세 스윕 모두 토글 무관 항상 발송.

---

## 4. 경쟁 검수 모델

- **정의:** 동일한 AI 초안을 여러 사정사가 **독립적으로** 채택·검수·등록할 수 있는 구조
- **목적:** 사용자가 복수의 검수 리포트를 비교하여 원하는 사정사를 직접 선택
- **격리 규칙:** 각 사정사의 수정 내용은 본인 작업 공간에만 반영됨 (타 사정사와 격리)
- **제거 시점:** 채택해도 목록에서 제거되지 않음 — 사용자가 매칭을 확정한 시점에 해당 케이스가 다른 사정사의 채택 가능 목록에서 제거됨

---


## 5. 매칭(제안 선택) 플로우 상세

> ⚠️ 실제 구현에는 별도 `match` 도메인/엔티티/컨트롤러가 없다(`domain/match`에 `.gitkeep`만 존재, `MATCHING_*` 에러코드는 예약만 되고 미사용). 매칭은 **report 제안(REPORT_REVIEWS) 선택 + chat 상담 결정**으로 구현된다.

### 제안 조회·선택
- 사용자가 검수 제안 목록에서 사정사를 **직접 선택** (알고리즘 추천 아님)
- 제안 목록 조회: `GET /reports/{reportId}/proposals` (검수 등록된 REPORT_REVIEWS 목록)
- 제안을 선택해 상담을 시작하면 리포트가 `AWAITING_ADOPTION → COUNSELING`으로 전이되고 ChatRoom(WebSocket)이 개설된다.

### 상담 결정 (수락/거절) — 사용자(방 소유자)만
- **수락(확정)**: `PATCH /chats/{chatRoomId}/accept` — 내 제안 `ACCEPTED`, 리포트 `COUNSELING → CLOSED`, 형제 제안(다른 사정사) `REJECTED` + 형제 채팅방 CLOSED. 내 방은 ACTIVE로 유지해 대화 지속. `PATCH /reports/{reportId}/proposals/{proposalId}`(Body `{ "status": "ACCEPTED" }`)도 동일하게 리포트를 CLOSED로 확정한다.
- **거절**: `PATCH /chats/{chatRoomId}/reject` — 내 제안 `REJECTED`, 리포트 `COUNSELING → AWAITING_ADOPTION`(다른 제안 재선택 가능) + 방 CLOSED. `PATCH /reports/{reportId}/proposals/{proposalId}`(Body `{ "status": "REJECTED" }`)는 해당 제안만 REJECTED로 두고 리포트 상태는 유지한다.
- 수락/거절 주체는 방 소유자(user)다 — 아니면 `CHAT_NOT_ROOM_OWNER(403)`, 결정 불가 방이면 `CHAT_CONSULTATION_UNAVAILABLE(409)`.

### 제약
- 보험업법 §189: 협상·합의 대리 기능 미제공. 정보 제공과 사정사 연결만 수행.
- CLOSED 트리거는 **사용자의 제안 수락**이다(사정사 리포트 등록이 아님). COUNSELING에서 거절하면 AWAITING_ADOPTION으로 되돌아간다.

---

## 7. 인증·토큰 정책

| 항목 | 값 | 비고 |
|------|-----|------|
| Access Token 만료 | **30분** | JWT stateless (`jwt.access-token-expiry=1800000`) |
| Refresh Token 만료 | **14일** | Redis TTL 저장 (`refresh:{userId}`, `jwt.refresh-token-expiry=1209600000`) |
| Redis 저장소 | ElastiCache Redis | |
| 소셜 로그인 | 카카오, 네이버 OAuth2 | `provider`: `kakao` / `naver` |
| CSRF 방지 | OAuth2 콜백 시 `state` 파라미터 사용 (선택) | |
| 비밀번호 | 소셜 전용이라 비밀번호 없음 | 비번 변경 API 불필요 |
| 로그인 실패 응답 | 미존재 아이디·비밀번호 불일치 **동일 메시지** | 계정 존재 여부 노출 방지 |
| 신규 사용자 판별 | 로그인 콜백 응답에 `isNewUser: boolean` 포함 | |
| Device Token | 로그인 시 FCM device token 함께 등록 | 푸시 알림 수신용 |
| 로그아웃 멱등 처리 | 이미 로그아웃 상태여도 정상 처리 (중복 요청 무시) | |
| 소셜 계정 연결 | `social_accounts` 테이블로 소셜 ID ↔ 내부 User 매핑 | |

---

## 8. 구독 플랜 (손해사정사 전용)

| 플랜  | API `tier` 값 | `subscription_plan` DB 값 | 포함 혜택 |
|-----|-------------|--------------------------|---------|
| 미검증 | — | `none` | 미검증 손해사정사는 손해 사정 관련 어떤 것도 할 수 없다 |
| 기본  | `BASIC` | `basic` | AI 리포트 열람·수정·서명, 케이스 채택 |
| 프로  | `PRO` | `premium` | 기본 + 검수 리포트 목록 상단 노출 |

> `POST /subscriptions` Body: `{ "tier": "BASIC" | "PRO", "paymentMethod": "pg_token_xxx" }`  
> 미검증 사정사가 구독 시도 시 `FORBIDDEN(403)` 반환.

- 결제 주기: MONTHLY
- PG사: 토스페이먼츠
- 구독 만료 시 상단 노출 혜택 즉시 해제
- 구독 응답 필드: `subscriptionId`, `tier`, `status: "ACTIVE"`, `expiresAt`
- **구독 취소·현재 구독 조회 API 미구현** — 별도 엔드포인트 추가 검토 필요 `[미결]`

---

## 9. 사정사 자격 신청 플로우

### 신청 (`POST /users/adjuster-applications`)
| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `name` | string | Y | 실명 |
| `phone` | string | Y | 연락처 |
| `specialties` | string[] | Y | 전문분야 복수(NotEmpty). 단수 `speciality`(구)는 V19에서 `specialties text[]`로 복수화됨 |
| `licenseNo` | string | N* | 자격증 번호 (PDF 미제출 시 필수) |
| `licenseImageUrl` | string | N* | 자격증 PDF S3 URL (번호 미입력 시 필수) |
| `career` | int | N | 연차 |
| `introduction` | string | N | 자기소개 (구 `introduce` 오타 정정) |
| `affiliation` | string | Y | 소속 형태 코드 `INDEPENDENT` / `FIRM` (enum `Affiliation`) |
| `region` | string | Y | 활동 지역 |
| `registrationImageUrl` | string | Y | 등록증 파일 S3 URL |

> `licenseNo`와 `licenseImageUrl` 중 **최소 하나는 필수**(`hasLicenseProof`, 서비스에서 검증 — 위반 시 `MISSING_REQUIRED_FIELD`).
> 신청 접수 시 증빙 문서 2종(`LICENSE`/`REGISTRATION`, enum `DocumentType`)이 `PENDING`(enum `DocumentStatus`: `PENDING`/`APPROVED`/`RESUBMIT_REQUIRED`)으로 `ADJUSTER_APPLICATION_DOCUMENTS`에 함께 생성된다(V15).

### 신청서 상태 (`ADJUSTER_APPLICATIONS.status`)
| 값 | 설명 |
|----|------|
| `PENDING` | 심사 대기 중 |
| `APPROVED` | 승인 완료 (ADMIN 처리, 액션 엔드포인트 `/accept`) → `CERTIFICATED_ADJUSTER`로 활성화 |
| `REJECTED` | 반려 (reason 필드 포함 가능) |

> ERD status 종료상태는 `APPROVED`(승인)다. 관리자 승인 액션 엔드포인트 명칭이 `/accept`라 예전엔 상태도 `ACCEPTED`로 적었으나, ERD·상태값 정본은 `APPROVED`로 통일한다(코드 엔티티 미생성 — 추후 매핑 시 `APPROVED` 사용).

### 관리자 처리
- 승인: `POST /admins/adjuster-applications/{applicationID}/accept`
- 반려: `POST /admins/adjuster-applications/{applicationID}/rejects` (Body: `{ "reason": "..." }`)
- 이미 처리된 신청에 재처리 시: `UNSUPPORTED_OPERATION(400)`
- 승인 시: 해당 유저를 `CERTIFICATED_ADJUSTER`로 전환 + 프로필 활성화 + 신청자 알림 발송
- 자격번호+이름을 금융위원회 공식 명부와 대조 권장 (현재 수동 처리)

---

## 10. 주요 API 엔드포인트 (Spring Boot 담당)

> base URL = `https://example.com/api/v1`  
> 로그인은 OAuth2 소셜 로그인만 사용. 자체 로그인 없음.

### auth 도메인
| 기능 | Method | Path |
|------|--------|------|
| 회원가입 | POST | `/auth/register` |
| 소셜 로그인 콜백 | GET | `/auth/oauth2/{provider}/callback` |
| 토큰 갱신 | POST | `/auth/refresh` |
| 로그아웃 | POST | `/auth/logout` |

#### `POST /auth/register` 요청 필드

JSON 키는 Jackson 전역 SNAKE_CASE 적용 결과다(`RegisterRequest`).

| JSON 키 | 타입 | 필수 | 저장 위치 | 설명 |
|---------|------|------|-----------|------|
| `provider` | string | Y | `social_accounts.provider` | `kakao`\|`naver`\|`apple`. 가입 티켓의 provider와 일치해야 한다 |
| `social_token` | string | Y | - | 콜백에서 발급된 가입 티켓(short-lived JWT) |
| `name` | string | Y | `users.nickname` | **이름(실명)**, 1~30자. 정식 키 |
| `nickname` | string | N | `users.nickname` | `name`의 구 계약 별칭(`@JsonAlias`). 하위호환 전용이며 신규 클라이언트는 사용 금지. `name`과 **동시 전송 금지**(중복 시 JSON에서 나중에 온 값이 이긴다) |
| `birth_date` | string(ISO date) | Y | `users.birth_date` | 과거 날짜 |
| `phone_number` | string | Y | `users.phone_number` | `^01[0-9]-?\d{3,4}-?\d{4}$`, UNIQUE |
| `gender` | string | Y | `users.gender` | 10자 이하. 빈 문자열 허용 |
| `region` | string | N | `users.region` (`text[]`) | 활동/거주 지역, 100자 이하. 복수 지역은 `·`로 연결한 **단일 문자열**(`"서울·경기"`)로 보내고 서버가 `RegionFormat.toList`로 배열 변환한다. 미전송·빈 문자열·`·`뿐이면 빈 배열이 아니라 **NULL**로 저장 |
| `user_type` | string | Y | `users.role` | `insured_person`\|`adjuster` → Role 매핑 |

> **응답의 `nickname` 키는 요청과 비대칭이다.** `RegisterResponse`는 기존 계약 유지를 위해 `nickname`으로 내려준다(요청 정식 키는 `name`). 응답까지 `name`으로 통일하는 것은 브레이킹 체인지라 FE 합의 후 별도 이슈로 처리한다.

### user 도메인
| 기능 | Method | Path |
|------|--------|------|
| 내 정보 조회 | GET | `/users/me` |
| 내 정보 수정 | PATCH | `/users/me` |
| 회원 탈퇴 | DELETE | `/users/me` |
| 사정사 자격 신청 | POST | `/users/adjuster-applications` |

### report 도메인
| 기능 | Method | Path |
|------|--------|------|
| 리포트 생성 요청 (비동기) | POST | `/reports` |
| 리포트 목록 조회 | GET | `/reports?status={status}&page={page}` |
| 리포트 상세 조회 | GET | `/reports/{reportID}` |
| 리포트 검수·수정 | PATCH | `/reports/{reportID}` |
| 분석(OCR·AI) 처리 상태 폴링 조회 | GET | `/reports/{reportId}/analysis-status` |
| 받은 제안 목록 조회 | GET | `/me/received-proposals?page={page}` |

### adjuster 도메인
| 기능 | Method | Path |
|------|--------|------|
| 사정사 홈 대시보드 집계 | GET | `/adjusters/me/home` |

> 홈 대시보드는 요약 카드(검수 대기 풀·진행 중·이번 달 완료·누적·상담 전환·평점) + 진행 중 사건 미리보기를 1회 호출로 내리는 조회 전용 BFF다. 누적 검수·상담·평점은 `adjuster_profiles` 비정규화 컬럼에서, '이번 달 완료'만 `report_reviews` 실시간 집계로 낸다. 검수 대기 목록은 미포함(프론트가 검수 대기 목록 API로 조회). `CERTIFICATED_ADJUSTER`·`UNCERTIFICATED_ADJUSTER`만 접근(그 외 403). 조회는 `AdjusterHomeRepository`(QueryDSL 크로스-애그리거트 읽기 모델)가 담당한다.
> `/adjusters/me/reviewed-reports`(내 검수 내역, API#5)는 아직 report 도메인 코드에 있다 — 경로만 adjuster-facing.

### 매칭(제안 선택) — report/chat 도메인
> 별도 `/matches` 엔드포인트·`match` 도메인은 없다(구 `POST /matches/{reportID}` 폐기). 매칭은 아래 제안 조회 + 상담 결정으로 구현된다.

| 기능 | Method | Path |
|------|--------|------|
| 검수 제안 목록 조회 | GET | `/reports/{reportId}/proposals` |
| 제안 채택/거절 | PATCH | `/reports/{reportId}/proposals/{proposalId}` (Body `{ "status": "ACCEPTED" \| "REJECTED" }`) |
| 상담 수락(확정) | PATCH | `/chats/{chatRoomId}/accept` |
| 상담 거절 | PATCH | `/chats/{chatRoomId}/reject` |

### chat 도메인
| 기능 | Method | Path |
|------|--------|------|
| 채팅방 목록 조회 | GET | `/chats` |
| 채팅방 상세 조회 | GET | `/chats/{chatRoomId}` |
| 읽음 처리 | POST | `/chats/{chatRoomId}/read` |

> 메시지 송수신은 WebSocket(STOMP)으로 처리. REST는 목록/상세/읽음·상담 결정만.

### notification 도메인
| 기능 | Method | Path |
|------|--------|------|
| 내 알림 목록 조회 | GET | `/users/me/notifications` |
| 알림 전체 읽음 | PATCH | `/users/me/notifications/read-all` |
| 알림 1건 읽음 | PATCH | `/users/me/notifications/{notificationId}/read` |
| 알림 설정 조회 | GET | `/users/me/notification-settings` |
| 알림 설정 수정 | PATCH | `/users/me/notification-settings` |

### payment 도메인 `[계획/미구현]`
> payment·subscription 도메인은 아직 코드에 없다(엔티티·컨트롤러·서비스 부재, `PAYMENT_FAILED` 에러코드만 예약). 아래 경로는 기획상 계획이며 현재 미구현이다.

| 기능 | Method | Path |
|------|--------|------|
| 구독 신청 `[미구현]` | POST | `/subscriptions` |
| 결제 내역 조회 `[미구현]` | GET | `/payments/history` |

### admin 도메인
| 기능 | Method | Path |
|------|--------|------|
| 자격 신청 목록 조회 | GET | `/admins/adjuster-applications` |
| 자격 신청 승인 | POST | `/admins/adjuster-applications/{applicationID}/accept` |
| 자격 신청 반려 | POST | `/admins/adjuster-applications/{applicationID}/rejects` |

---

## 11. 에러 코드 전체 목록

```
// 400 Bad Request — 요청 자체가 잘못됨 (클라이언트 잘못)
INVALID_REQUEST          // 요청 형식/구조 이상 (깨진 JSON, 타입 불일치, 잘못된 파라미터)
VALIDATION_ERROR         // 필드 값이 검증 규칙 위반 (형식·길이·범위 등)
MISSING_REQUIRED_FIELD   // 필수 입력값 누락
UNSUPPORTED_OPERATION    // 허용되지 않는 동작 (MVP 미지원 보험사, 이미 처리된 신청 재처리 등)

// 401 Unauthorized — 인증 실패
INVALID_TOKEN            // 토큰 위조·변조 또는 서명/형식 오류
EXPIRED_TOKEN            // 토큰 유효기간 만료 → Refresh로 재발급 필요
LOGIN_REQUIRED           // 비로그인 상태로 인증 필요 리소스 접근

// 403 Forbidden — 인증은 됐지만 권한 없음
FORBIDDEN                // 권한 부족 (미활성 사정사 채택 API, 타인 리포트 접근, 비 ADMIN 등)

// 404 Not Found — 대상 리소스 없음
USER_NOT_FOUND           // 해당 사용자 없음
POST_NOT_FOUND           // 게시물/리포트 없음 (구 code — REPORT_NOT_FOUND와 공존, 일원화 안 됨)
REPORT_NOT_FOUND         // 해당 리포트 없음
SUBSCRIPTION_NOT_FOUND   // 해당 구독 정보 없음

// 409 Conflict — 현재 리소스 상태와 충돌
DUPLICATE_RESOURCE       // 이미 존재하는 리소스 재생성 (닉네임 중복, 이미 진행 중인 상담 등)
INVALID_STATE_TRANSITION // (409) 리소스 상태 충돌 (COUNSELING 아닌 제안 채택 등) — 아래 INVALID_STATUS_TRANSITION(400)과 별개로 공존
CLOSED                   // (409) 종료된 리소스에 대한 요청 (비활성 상담방 메시지 전송 등)

// 422 Unprocessable Entity — 형식은 맞으나 비즈니스 규칙상 처리 불가
PAYMENT_FAILED           // 결제 처리 실패 (PG 거절, 카드 한도 초과, 잔액 부족 등)

// 500 Internal Server Error — 서버 내부 오류
INTERNAL_SERVER_ERROR    // 처리되지 않은 서버 예외
DATABASE_ERROR           // DB 조회/저장 실패
EXTERNAL_API_ERROR       // 외부 연동 실패 (PG, 카카오/네이버 OAuth, OCR, LLM 등)

// 503 Service Unavailable — 서버 일시 이용 불가
SERVICE_UNAVAILABLE      // 점검·배포·과부하 (보통 Retry-After 헤더 동반)

// 도메인 특화 코드 (ErrorCode.java 실재 — 위 공통 카탈로그와 함께 존재)
BAD_REQUEST              // (400) @Valid 바디 검증 실패 시 전역 400 code
REFRESH_TOKEN_NOT_FOUND  // (401) 리프레시 토큰 없음
UNSUPPORTED_PROVIDER     // (400) 미지원 소셜 로그인 제공자
ADJUSTER_NOT_FOUND       // (404) 손해사정사 없음
REPORT_ISSUE_NOT_FOUND   // (404) 리포트 쟁점 없음
INVALID_STATUS_TRANSITION// (400) 검수 생명주기 전이 위반 (INVALID_STATE_TRANSITION 409와 별개)
PROPOSAL_NOT_FOUND       // (404) 제안(REPORT_REVIEWS) 없음
REPORT_ALREADY_CLOSED    // (409) 이미 종결된 리포트
CLAIM_DETAILS_TYPE_MISMATCH // (400) 청구 상세 유형이 사고 유형과 불일치
MATCHING_NOT_FOUND / MATCHING_ALREADY_EXISTS // (404/409) 예약만 — match 도메인 미구현
CHAT_ROOM_NOT_FOUND / CHAT_NOT_A_MEMBER / CHAT_NOT_ROOM_OWNER / CHAT_CONSULTATION_UNAVAILABLE / CHAT_ROOM_CLOSED / CHAT_ATTACHMENT_* / CHAT_WS_UNAUTHORIZED // 채팅 도메인
NOTIFICATION_NOT_FOUND   // (404) 알림 없음
```

> ⚠️ 위는 **핵심 카탈로그**다. 실제 `ErrorCode.java`에는 구 code(`POST_NOT_FOUND`·`INVALID_STATUS_TRANSITION`)가 신규 code와 함께 잔존하며 도메인 특화 code가 다수 있다 — 정본은 `ErrorCode.java`.

---

## 12. 컴플라이언스 제약 (법령 근거)

| 규칙 | 근거 법령 | 코드 영향 |
|------|---------|---------|
| 금융위 등록 자격자만 사정사 활성화 허용 | 보험업법 §186 | `ADJUSTER` 계정 활성화 ADMIN 수동 처리. 미활성화 시 채택 API 403 |
| 확정 결과물에 등록 손해사정사 서명·자격 표시 필수 | 보험업법 §188 | publish API 요청 시 `adjuster_license_no`, `adjuster_name`, `signed_at` 필수 |
| 서명된 리포트 3년 보존 | 보험업법 §188 / 개인정보보호법 | 탈퇴 시 식별정보 익명화, 리포트 원본은 3년 유지 |
| 협상·합의 대리 기능 미제공 | 보험업법 §189 | 매칭 기능은 연결만. 대리 행위로 해석될 UI·API 문구 금지 |
| 보존 기간 경과 후 개인정보 파기 | 개인정보보호법 §21 | 3년 경과 후 파기 스케줄러 필요 `[미구현]` |
| 보상금액 단정 표현 금지 | 금소법 §17·§19 | API 응답에 확정적 보상금액 직접 노출 금지. `claimedMinAmount` / `claimedMaxAmount` 범위로만 표현 |
| 진단·의료 판단 표현 금지 | 의료법 §17·§22 | AI 리포트 응답에 의학적 단정 문구 금지 |

---

## 13. 핵심 테이블 목적 정의

### REPORT_REVIEWS (사정사 검수 테이블)
- **목적**: 사정사가 리포트에 남기는 **고객 제공 최종 검수 내용**(의견·예상금액·보장/특약/근거 수정본). AI 초안(REPORTS)과 **별개 테이블**로 격리.
- **생성 시점**: 사정사가 검수(PATCH /reports/{id})를 최초 반영할 때 행 upsert (채택 게이팅은 현재 미적용 — role=CERTIFICATED_ADJUSTER면 허용)
- **격리 규칙**: 경쟁 검수 모델 — 동일 AI 초안에 여러 사정사의 REPORT_REVIEWS 행이 존재. 조회 시 반드시 `adjuster_id` 필터링. **AI 초안(REPORTS/REPORT_ISSUES)은 절대 덮어쓰지 않음.**
- **주요 필드**: `review`(사정사 최종 의견, 고객 노출), `estimate_min_amount`/`estimate_max_amount`, `applicable_guarantees[]`/`omitted_special_contract[]`/`basis_terms_precedents[]`(사정사 수정본), `status`(SENT/COUNSELING/REJECTED/ACCEPTED — ERD 2026-07 정합)
- **RAG 피드백**: AI 개선 피드백은 본 범위 제외(다음 티켓). 현재 `review`는 고객 노출 최종 의견 용도.

### REPORT_ISSUES_REVIEWS (사정사별 쟁점 검수 테이블)
- **테이블명**: `report_issues_reviews` (V10에서 `report_review_issues` → `report_issues_reviews`로 리네이밍)
- **목적**: 사정사가 쟁점(REPORT_ISSUES=AI 초안)을 검수·수정하거나 **신규 추가(ADDED)** 한 결과를 사정사별로 격리 저장
- **생성 시점**: 검수 반영(PATCH) 시 해당 REPORT_REVIEWS 하위로 쟁점 upsert 저장
- **주요 필드**: `report_issue_id`(nullable — null이면 사정사 신규 쟁점), `title`/`description`(신규·수정 내용), `impact_amount`(사정사 확정 영향금액 — V9 추가), `review_status`(ACCEPTED/MODIFIED/EXCLUDED/**ADDED**), `adjuster_opinion`/`modified_reason`/`excluded_reason`
- **관계**: REPORT_REVIEWS 1:N REPORT_ISSUES_REVIEWS, REPORT_ISSUES 1:N REPORT_ISSUES_REVIEWS(nullable)

### ADJUSTER_REVIEWS (사용자 평가 테이블)
- **테이블명**: `adjuster_reviews`(복수형)
- **목적**: 매칭 완료(CLOSED) 후 사용자가 담당 사정사를 평가한 기록
- **생성 시점**: 사용자가 매칭 종료 후 평가 제출 시 (평가 수집 쓰기 경로는 별도 티켓 — 현재 엔티티는 조회 전용 매핑)
- **필드**: `user_id`, `adjuster_id`, `report_id`(사건 연결 — V26 추가, nullable), `score`(정수, 엔티티 매핑), `review`(텍스트 — DB 컬럼 존재하나 현재 엔티티 미매핑)
- **제약**: 사용자 1인 + 사정사 1인 조합으로 중복 평가 방지

### REPORT_HOLDS (사정사별 보류 테이블)
- **목적**: 검수 대기 화면에서 사정사가 사건을 보류한 기록(보류 모달). junction — (report_id, adjuster_id) UK.
- **필드**: `report_id`, `adjuster_id`, `reason`(enum `HoldReason`: `NEED_MORE_DOCUMENTS`/`OUT_OF_SPECIALTY`/`SCHEDULE_CONFLICT`/`OTHER`), `reason_detail`(V7 추가 — `OTHER`면 필수)

### NOTIFICATIONS (인앱 알림함)
- **목적**: 사용자별 인앱 알림 1건(제목·본문·읽음 여부). 발송 토큰(device_tokens)·수신 설정(notification_settings)과 분리된 알림함 목록/읽음 전용(V18).
- **필드**: `user_id`, `type`(enum `NotificationType`, varchar 저장), `title`, `body`, `is_read`, `created_at`
- **NotificationType 값(14개)**: 고객계 `REVIEW_COMPLETE`/`RECEIVED_PROPOSAL`/`CONSULT_ACCEPTED`/`ANALYSIS_COMPLETE`/`ANALYSIS_FAILED`/`REPORT_BLOCKED`/`REPORT_NEEDS_REUPLOAD`/`IDENTITY_VERIFIED`/`CHAT_MESSAGE`/`SETTLEMENT_NOTICE`/`PROPOSAL_CLOSED`, 사정사계 `NEW_REVIEW_REQUEST`/`REVIEW_DEADLINE_SOON`/`CONSULT_REQUESTED`. `ANALYSIS_FAILED`(분석 확정 실패)·`REPORT_BLOCKED`(가드레일 차단)·`REPORT_NEEDS_REUPLOAD`(OCR 품질 미달)는 시스템 실패 통지라 `NotificationSetting.allows()`에서 토글 무관 항상 발송(`PROPOSAL_CLOSED`와 동일 취급) — `notification_settings`에 대응 컬럼 없음, 추가도 불필요(varchar 저장이라 마이그레이션 없이 enum만 늘리면 된다).
- **비고**: 생성 배선(이벤트→row insert)은 별도 티켓 범위. 생성 후 읽음만 전이(updated_at 없음).
- **네이밍 규칙**: `report` 도메인이 원인인 시스템 실패 통지는 `REPORT_` 접두어를 붙인다(`REPORT_BLOCKED`·`REPORT_NEEDS_REUPLOAD`). 접두어 없이 `reports.status`·`AnalysisState` 값과 동일한 이름(`BLOCKED`·`NEEDS_REUPLOAD`)을 그대로 쓰면 FE가 `notifications.type`/FCM `data.type`과 응답 `status`/`analysis_state`를 같은 네임스페이스로 오인할 수 있다(PR #251에서 CodeRabbit이 지적해 배포 전 정정한 사례 — `NEEDS_REUPLOAD` → `REPORT_NEEDS_REUPLOAD`). 새 `NotificationType` 값을 추가할 때 형제 값과 접두어 일관성을 먼저 확인할 것.

### NOTIFICATION_SETTINGS (알림 수신 토글)
- **목적**: 사용자별 알림 수신 on/off 토글(USERS 1:1, user_id PK). off면 해당 type 미발송(producer 배선에서 적용).
- **토글**: `new_review_request`·`consult_message`·`settlement_notice`·`review_deadline_soon`·`review_complete`·`received_proposal`·`consult_accepted`·`analysis_complete`·`identity_verified`·`marketing`. V21에서 consult_accepted·analysis_complete·identity_verified·review_deadline_soon 4종 추가. 기본값은 정산·마케팅만 false.


---

## 14. ERD 핵심 필드 참조

### REPORTS (AI 초안 — 불변)
| 필드 | 타입 | 설명 |
|------|------|------|
| `case_no` | varchar(100) | 사람용 사건번호 `yyyyMMdd-NNN`(당일 시퀀스 발급) |
| `title` | varchar | 리포트 제목(nullable) |
| `accident_type` | enum | `medical_indemnity, traffic, disability, cancer_diagnosis, fire, liability, other` (영문) |
| `status` | enum | `AWAITING_INSPECTION`, `AWAITING_ADOPTION`, `COUNSELING`, `CLOSED`, `NOT_SELECTED`, `BLOCKED`(AI 워커가 원시 SQL로 직접 세팅, §3-1 참고), `NEEDS_REUPLOAD`(AI 워커가 원시 SQL로 직접 세팅, OCR 품질 미달, §3-1 참고). DB는 varchar(30) — CHECK 제약·PostgreSQL enum 없음, 값 목록은 Java enum(`ReportStatus`)에서만 강제된다 |
| `analysis_failure_notified_at` | timestamp(nullable) | 분석 확정 실패 알림 발송 시각(V41). NULL=미통지, 스윕의 멱등 가드. 분석 상태 자체는 저장 안 함(§3-1) |
| `blocked_notified_at` | timestamp(nullable) | BLOCKED 알림 발송 시각(V42). NULL=미통지, 별도 스윕의 멱등 가드 |
| `needs_reupload_notified_at` | timestamp(nullable) | NEEDS_REUPLOAD 알림 발송 시각(V43). NULL=미통지, 별도 스윕(`NeedsReuploadNotificationSweeper`)의 멱등 가드 |
| `claimed_min_amount` | bigint | 최소 청구 금액 (단정 표현 금지 — 범위로 표현) |
| `claimed_max_amount` | bigint | 최대 청구 금액 |
| `offered_amount` | bigint | 보험사 지급 금액 |
| `applicable_guarantees` | string[] | 적용 가능 보장 목록 (AI 원본 — 사정사 수정본은 REPORT_REVIEWS) |
| `omitted_special_contract` | string[] | 누락 가능 특약 목록 (AI 원본) |
| `basis_terms_precedents` | string[] | 근거 약관·판례 (AI 원본) |
| `treatment` | text | 질병명 |
| `question` | text | 사용자 질문 입력 |
| `confidence_level` | varchar(10) | AI 초안 신뢰수준 문자열(nullable, FastAPI 산출 읽기전용 — 코드에 enum 검증 없음, `HIGH`/`MEDIUM`/`LOW` 관례값) |
| `is_masked` | boolean | 본문·첨부 PII 마스킹 적용 여부(OCR 마스킹 결과 기반) |
| `documents` | jsonb | `{name: s3_url}` 첨부 비정규화 맵 — **검수 대기 화면(API#6) 첨부 표기용**. 상세 첨부는 REPORT_ATTACHMENTS(리치) |
| `adjuster_id` | uuid | 담당 사정사 ID (매칭 전 null) |

> 쟁점은 `REPORTS.issue[]` 배열이 아니라 **REPORT_ISSUES 테이블**로 분리(AI 초안). 사정사 검수 결과는 **REPORT_ISSUES_REVIEWS**(격리, `report_issues_reviews`).
> `region`은 REPORTS에 없음 → 검수 화면 노출 시 `USERS.region` 조인(비식별).
> ⚠️ `POST /reports` 요청 파라미터 `accidentType`(신체/교통 명세)과 DB enum(영문) 매핑은 서버 내부 처리.
> REPORT_ISSUES.`ai_status`: `CONFIRMED`/`TRUSTED`/`INFO` (AI 쟁점 신뢰등급, FastAPI 산출 — Spring은 읽기만).
> REPORT_ATTACHMENTS(상세 첨부, 검수 화면 소스): `name`·`mime_type`·`url`(s3)·`report_type`·`page_count`·`issued_by`·`issued_at`·`ai_summary`·`ocr_result_id`.

### ADJUSTER_PROFILES
> `domain/adjuster/entity/AdjusterProfile`로 매핑(1:1 USERS, user_id UK). 누적 검수·상담·평점은 비정규화 컬럼이며 갱신 책임은 검수 완료·상담·후기 write 로직에 있다(현재 미구현이라 null 가능).

| 필드 | 타입 | 설명 |
|------|------|------|
| `user_id` | uuid | USERS 1:1 연결 (UK) |
| `license_no` | varchar | 금융위원회 등록번호 |
| `name` | varchar | 사정사 표시 이름 |
| `headline` | varchar | 한 줄 소개 |
| `specialties` | text[] | 전문분야 복수(후유장애·교통사고·장해등급 재산정 등) |
| `career` | int | 연차(수동 입력) |
| `cases_accepted` | int | 누적 채택 수(비정규화) |
| `cases_reviewed` | int | 누적 검수 수(비정규화) |
| `completed_consult_count` | int | 상담 완료 수(비정규화) |
| `rating_mean` | numeric | 평균 평점(비정규화, 후기 등록 시 갱신) |
| `review_count` | int | 후기 수(비정규화) |
| `careers` | jsonb | 주요 경력 `[{period, company}]` |
| `consult_methods` | text[] | 상담 방식(복수) |
| `activity_region` | text[] | 활동 지역(복수 — V23에서 배열 전환) |
| `verified_at` | timestamp | 자격 검증 시각 |
| `introduction` | text | 자기소개 |
| `registration_url` | text | 등록증 URL (V22 추가) |
| `updated_at` | timestamp | 수정 시각 (BaseEntity, V22 정렬) |

> ⚠️ 구독 플랜은 ADJUSTER_PROFILES가 아니라 **SUBSCRIPTIONS.plan**이 단일 진실(`none`/`basic`/`premium`)이다 — ERD·스키마에 `adjuster_profiles.subscription_plan` 컬럼은 없다(구 표기 정정). `speciality varchar`(단수) 표기도 실제는 `specialties text[]`(복수 배열)로 정정.

### SUBSCRIPTIONS `[테이블만 존재, 엔티티 미구현]`
> V1에 테이블만 있고 대응 엔티티/도메인은 없다. 타입은 varchar/timestamp(아래), enum 값은 애플리케이션 관례.

| 필드 | 타입(V1 스키마) | 설명 |
|------|------|------|
| `adjuster_id` | uuid (NOT NULL, FK users) | 구독 소유 사정사 |
| `plan` | varchar(20) | `none`/`basic`/`premium` 관례값 |
| `billing_cycle` | varchar(20) | `MONTHLY` 관례값 |
| `status` | varchar(20) | `ACTIVE`/`EXPIRED`/`CANCELED` 관례값 |
| `started_at` | timestamp | 구독 시작 시각 |
| `expires_at` | timestamp | 구독 만료 시각 |
| `next_billing_at` | timestamp | 다음 결제 예정 시각 |

### PAYMENTS `[미구현/계획 — 스키마 없음]`
> ⚠️ `payments` 테이블은 어떤 마이그레이션에도 존재하지 않으며 엔티티도 없다. 아래는 기획상 계획 필드다(실재 스키마 아님). `PAYMENT_FAILED` 에러코드만 예약되어 있다.

| 필드(계획) | 타입 | 설명 |
|------|------|------|
| `amount` | int | 결제 금액 (원) |
| `type` | enum | `SUBSCRIPTION` |
| `status` | enum | `PAID` |
| `paid_at` | datetime | 결제 완료 시각 |

### ADJUSTER_APPLICATIONS
| 필드 | 타입 | 설명 |
|------|------|------|
| `user_id` | uuid | 신청 사용자 |
| `name` | varchar | 실명 |
| `phone` | varchar(20) | 연락처 (V19 추가) |
| `specialties` | text[] | 전문분야 복수 (V19에서 단수 `speciality varchar` → 복수 배열로 전환) |
| `license_no` | varchar | 자격증 번호 (nullable) |
| `license_image_url` | varchar | 자격증 PDF S3 URL (nullable) |
| `career` | int | 연차 (nullable) |
| `introduction` | text | 자기소개 (nullable) — 컬럼명은 `introduction`(구 `introduce` 오타 정정) |
| `affiliation` | enum | 소속 형태 `INDEPENDENT` / `FIRM` (enum `Affiliation`) |
| `region` | varchar | 활동 지역 |
| `registration_image_url` | varchar | 등록증 파일 S3 URL |
| `status` | enum | `PENDING`, `APPROVED`, `REJECTED` (ERD 정합 — 구 `ACCEPTED` 표기 정정) |
| `reject_reason` | text | 반려 사유 (nullable) |
| `rejected_at` | timestamp | 반려 시각 (nullable) |

### ADJUSTER_APPLICATION_DOCUMENTS (증빙 문서 심사 — V15)
> ADJUSTER_APPLICATIONS 1:N. 신청 접수 시 문서 2종을 함께 생성한다(Aggregate 내부 Entity, application_id FK).

| 필드 | 타입 | 설명 |
|------|------|------|
| `application_id` | uuid | 소유 신청서 FK |
| `doc_type` | enum | `LICENSE` / `REGISTRATION` (enum `DocumentType`) |
| `status` | enum | `PENDING` / `APPROVED` / `RESUBMIT_REQUIRED` (enum `DocumentStatus`, 반려 시 재제출 요구) |

### REPORT_CASE_SEQUENCES (사건번호 시퀀스 — V14)
> `case_no`(yyyyMMdd-NNN)의 당일 시퀀스 카운터(`day` PK, `seq`). 동시 생성 경합을 막으려 DB 원자 카운터(ON CONFLICT)로만 증가시킨다.

---

## 15. API 응답 구조

```json
// 성공 시
{
  "status": "200",
  "message": "정상 처리되었습니다.",
  "data": { ... }
}

// 실패 시
{
  "status": "400",
  "code": "ERROR_CODE",
  "message": "에러 메시지"
}
```

> 리포트 생성(`POST /reports`)은 **비동기** — 202 Accepted 반환.  
> 결제 내역 조회 응답에는 영수증 발급·구독 해제가 별도 엔드포인트로 분리 권장 (미구현).

---

## 16. 기능별 비즈니스 규칙 요약 (기능리스트 출처)

> API 명세서에 없는 선행조건·트리거·예외 처리 규칙. 구현 시 반드시 참조.

### auth
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 회원가입 | 소셜 인증 완료, 미가입 상태 | social_accounts 연결 + 닉네임 등록 + JWT 발급 | 이미 연동된 소셜 계정 → 로그인 처리로 전환; 닉네임 중복 → 거부 |
| 소셜 로그인 | provider 동의 완료, 인가코드 수신 | 인가코드 → 소셜 토큰 교환 → 기존 회원: JWT 발급, 신규: 가입 플로우 연결; 로그인 시 device token 등록 | 미지원 provider; 인가코드 만료/무효 |
| 로그아웃 | 로그인 상태 (유효 토큰 보유) | Redis Refresh Token 폐기; 이미 로그아웃 상태여도 멱등 처리 | Access Token은 stateless — 30분 만료 전까지 유효 |
| 회원탈퇴 | 로그인 + 본인 확인 | 계정 익명화 + Refresh Token 삭제 + S3 접근 차단 | 진행 중 매칭/상담 처리 정책 미결 `[미결]`; 서명 완료 리포트는 3년 보존 |

### user
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 회원 정보 조회 | 로그인 | 본인 정보 반환; 비밀번호 등 민감 필드 제외 | 비로그인·만료 → 401 |
| 회원 정보 수정 | 로그인 | 닉네임·이메일 수정 (비밀번호 변경 없음) | 닉네임 중복 409; 형식 위반 400 |
| 사정사 자격 신청 | 로그인(USER), 미인증 사정사 | 신청서 생성(PENDING); 운영팀 승인 전까지 채택·검수 불가 | 이미 인증/신청 진행 중 → 중복 신청 불가 |

### report
| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 사건 정보 입력(리포트 생성) | 로그인(USER); 보험사·상품 선택 + 사고정보(USER_CLAIMS) 완료 | (Spring) 사고 입력 저장 + 진단서 S3 업로드 + OCR 트리거 SQS producer 발행 → 202 Accepted → (FastAPI consumer) 비동기 AI 파이프라인 수행 → 완료 시 푸시 알림 or polling | 미적재 약관/미지원 보험사; 필수값 누락; S3 업로드/SQS 발행 실패 → `EXTERNAL_API_ERROR(500)` |
| 리포트 목록 조회 | 로그인 | 사용자: 본인 리포트; 사정사: 본인 채택분 리포트; 타인 리포트 미노출 | — |
| 리포트 상세 조회 | 로그인; 본인 리포트 or 채택 사정사 | 검수 완료 리포트만 열람; 서명 전 AI 초안은 사용자에게 비노출 | 타인 접근 403; 미존재 404 |
| 초안 검수·수정 | 로그인(사정사); 채택 상태 | 리포트 body로 상태 전이 | 허용되지 않는 상태 전이; 권한 없음 403 |

> ⚠️ **미결:** 채택(adopt) 동작을 `PATCH /reports/{reportID}`로 처리하는지, 별도 엔드포인트가 필요한지 확인 필요.

### matching(제안 선택)
> 별도 match 도메인 없음 — report 제안(REPORT_REVIEWS) + chat 상담 결정으로 구현.

| 기능 | 선행조건 | 핵심 동작 | 주요 예외 |
|------|---------|---------|---------|
| 제안 목록 조회 | 검수 제안 1건 이상(AWAITING_ADOPTION); 로그인(USER) | GET /reports/{id}/proposals — 본인 리포트의 검수 제안 비교 | 본인 리포트 아님 → 403 |
| 상담 시작 | 제안 선택 | 리포트 AWAITING_ADOPTION → COUNSELING + 채팅방(WebSocket) 개설 | — |
| 상담 수락(확정) | COUNSELING; 방 소유자(USER) | PATCH /chats/{id}/accept(또는 proposals/{pid} status=ACCEPTED) → 리포트 CLOSED, 내 제안 ACCEPTED, 형제 제안·방 정리 | COUNSELING 아님 → 409; 방 소유자 아님 → 403 |
| 상담 거절 | COUNSELING; 방 소유자(USER) | PATCH /chats/{id}/reject → 리포트 AWAITING_ADOPTION 복귀 + 방 CLOSED(다른 제안 재선택 가능) | COUNSELING 아님 → 409; 방 소유자 아님 → 403 |

### chat
| 기능 | 선행조건 | 핵심 동작 | 비고 |
|------|---------|---------|------|
| 채팅방 목록 조회 | 로그인 | 본인 참여 채팅방 목록(마지막 메시지 포함) 반환 | 메시지 이력 조회 API 없음 (Spring Boot 범위 외) |
| 채팅 입장 | 로그인; 채팅방 참여자 | WebSocket(STOMP)으로 실시간 메시지 송수신 | REST API 없음; AI 챗봇은 FastAPI 담당 |

### payment
| 기능 | 선행조건 | 핵심 동작 | 비고 |
|------|---------|---------|------|
| 구독 신청 | 로그인(활성 사정사); PG 연동 | BASIC/PRO 플랜 결제 → 활성화 + 만료일 설정 | 구독 취소·현재 구독 조회 별도 필요 `[미결]` |
| 결제 내역 조회 | 로그인 | 본인 결제 내역 목록 반환 | 영수증 발급·구독 해제는 별도 엔드포인트 권장 `[미구현]` |

---

## 17. 변경 이력

| 날짜 | 변경 내용                                                                                                                                              | 사유 |
|------|----------------------------------------------------------------------------------------------------------------------------------------------------|------|
| 2026-08-16 | **OCR 품질 미달 상태(`NEEDS_REUPLOAD`) 반영**: §3 상태머신·전이표·표시문자열표에 `NEEDS_REUPLOAD`(OCR 품질 미달, 종료 상태, AI 워커 원시 SQL 세팅, 회복은 재업로드=새 리포트뿐) 추가. §3-1 판정 우선순위 4→5단계(`BLOCKED` 다음·AI 초안 검사보다 앞 — 초안 존재 시 오판 방지 + 저널 기반 실패 스윕과 중복 알림 차단), `AnalysisState` PROCESSING/COMPLETED/FAILED/BLOCKED→**+NEEDS_REUPLOAD 5값**, 알림 스윕 2종→**3종**(`NeedsReuploadNotificationSweeper` 신설). `analysis_failure_reason`(`AnalysisFailureReason`)엔 편입하지 않음(다른 테이블·다른 컬럼 판정이라 ACL 경계 유지) — `NEEDS_REUPLOAD`의 `failure_reason`은 항상 null. §13 NotificationType 13→**14개**(`REPORT_NEEDS_REUPLOAD`, 토글 무관 항상 발송 — 형제 값 네이밍 관례에 맞춰 `REPORT_` 접두어 사용, ai_owner 제안 원문 `NEEDS_REUPLOAD`에서 정정). §14 REPORTS.status에 `NEEDS_REUPLOAD`, `needs_reupload_notified_at`(V43, 알림 멱등 가드) 컬럼 추가 — `reports.status`엔 DB CHECK 제약이 없어(varchar(30), Java enum만 강제) 값 추가 자체엔 마이그레이션 불필요, V43는 가드 컬럼용. `ai.ocr_results`(문서 단위 상세) 연동은 `core.ocr_results` 동명이표 충돌 때문에 이번 스코프에서 제외, 후속 이슈로 분리. | ai_owner(FastAPI OCR 워커)의 무음 정지 신고 — BLOCKED와 동일 패턴으로 해결(#247·#248 후속) |
| 2026-08-15 | **리포트 분석 처리 상태 노출 기능 반영(PR #247·#248)**: §3 상태머신·전이표·표시문자열표에 `BLOCKED`(AI 입력 가드레일 차단, 종료 상태, 원시 SQL 세팅) 추가. §3-1 신설 — `AnalysisState`(REPORTS.status와 별도 축, PROCESSING/COMPLETED/FAILED/BLOCKED, DB 미저장 파생값)·판정 우선순위·`ai.ocr_job_failures`(AI 워커 소유 계약 테이블, `@Subselect` 읽기전용)·대표 실패 사유 우선순위·`GET /reports/{reportId}/analysis-status`(소유자 전용)·알림 스윕 2종 설명. §10 report 도메인에 analysis-status·`/me/received-proposals` 엔드포인트 추가. §13 NotificationType 11→13개(`ANALYSIS_FAILED`·`REPORT_BLOCKED` 추가, 토글 무관 항상발송). §14 REPORTS.status에 BLOCKED, `analysis_failure_notified_at`(V41)·`blocked_notified_at`(V42) 컬럼 추가. | OCR 처리 실패 알림 계약(`ai.ocr_job_failures`) 소비 기능 구현 후 하네스 동기화 |
| 2026-07-20 | **코드 정합 감사 반영(드리프트 18건)**: §3·§14 status에 `NOT_SELECTED` 추가(스케줄러 스윕, 비종료). §3·§5·§10·§16 매칭을 "제안(REPORT_REVIEWS) 수락/거절" 모델로 재작성 — CLOSED=사용자 수락, COUNSELING→AWAITING_ADOPTION 거절 경로, PATCH /chats/{id}/accept·reject·PATCH /reports/{id}/proposals/{pid}; 유령 `/matches` 엔드포인트·match 도메인 폐기 명시. §7·§16 토큰 만료 15분/30일→30분/14일. §9·§14 ADJUSTER_APPLICATIONS `speciality`→`specialties text[]`+phone·affiliation·region·registration_image_url·문서심사(ADJUSTER_APPLICATION_DOCUMENTS/Document*·Affiliation) 반영. §13 REPORT_REVIEW_ISSUES→`report_issues_reviews`(V10)+impact_amount(V9), ADJUSTER_REVIEW→`adjuster_reviews`+report_id(V26), REPORT_HOLDS·NOTIFICATIONS·NOTIFICATION_SETTINGS·NotificationType 추가. §14 REPORTS title·case_no 추가·confidence_level enum→varchar, ADJUSTER_PROFILES 누락필드 보강, SUBSCRIPTIONS 타입 정정+"엔티티 미구현", PAYMENTS "미구현/계획(스키마 없음)", REPORT_CASE_SEQUENCES(V14). §11 error code 구코드 공존·도메인코드 반영. 마이그레이션 번호 V12/V13→V22/V23 정정. | chore/harness-audit B_glossary 드리프트 감사(18건) 동기화 |
| 2026-07-14 | 사정사 홈 대시보드(GET /adjusters/me/home)를 report → **adjuster 도메인**으로 분리(섹션 10 adjuster 추가). ADJUSTER_PROFILES를 `AdjusterProfile` 엔티티로 매핑(§14 필드 정정: `speciality varchar`→`specialties text[]`, 구 `subscription_plan` 컬럼 없음 명시, `registration_url`·`updated_at` V22 추가 — 마이그레이션 재번호로 V12→V22). ADJUSTER_APPLICATIONS status ERD 정합(`ACCEPTED`→`APPROVED` §9·§14, `introduce`→`introduction`). **지역 배열화**: USERS.region·ADJUSTER_PROFILES.activity_region을 `text[]`로 전환(V23 — 재번호로 V13→V23, 복수 지역) — 검수대기 목록 지역 필터는 동등비교→`array_contains`. | #100 native→QueryDSL 리팩터 중 adjuster 도메인 분리 + 기존 엔티티 ERD 반영(지역 배열화 포함) |
| 2026-06-20 | OCR 처리 경계 반영: 사고 입력 수신·진단서 S3 업로드·OCR 트리거 Kafka producer를 Spring 범위로 명시(섹션 1·16). FastAPI는 consumer 측 OCR/AI 파이프라인 담당. | 사고 입력~OCR 트리거 구간 Spring 담당 결정 |
| 2026-06-14 | 매칭 플로우 수정: 사정사 수락 단계 제거. 사용자가 사정사 선택 시 즉시 COUNSELING 전이. `/matches/{reportID}/accept` API 삭제. 섹션 5·10·16 반영. | 실제 기획 확인 — 수락/거절 없는 즉시 연결 구조 |
| 2026-06-14 | 기능리스트 20개 페이지 동기화. device token, 로그아웃 멱등, 매칭 24h 만료, 거절 API 미결, 채택 API 미결, 구독 취소 미구현, USER_CLAIMS 선행조건, 기능별 비즈니스 규칙 표(섹션 16) 추가.                   | 기능리스트 기반 비즈니스 규칙 보완 |
| 2026-06-14 | Notion API 명세서 20개 페이지 전수 동기화. userType/Role 구분, 에러코드 전체, adjuster-applications 플로우, accidentType 불일치 주의, 매칭 경로(/matches), admin API, 결제/구독 상세 추가. | 최초 API 명세 기반 정합성 확보 |
| 2026-06-09 | 초기<br/> 구성                                                                                                                                         | 환경 세팅 완료 후 하네스 등록 |
