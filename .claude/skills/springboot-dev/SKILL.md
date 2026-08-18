---
name: springboot-dev
description: "Spring Boot 백엔드 피처 개발, API 구현, 버그 수정을 에이전트 팀으로 조율하는 메인 오케스트레이터. 손해사정사 매칭, 리포트, 결제, 인증, FCM Push, RBAC, WebSocket 채팅 관련 구현·수정·추가 요청 시 반드시 이 스킬을 사용. 인프라·관측성·배포 하드닝(actuator 헬스체크, JVM/GC, DB 커넥션 풀, SQS producer 설정·배선, docker healthcheck, PII 로깅, smoke test)도 이 스킬로 조율(infra-developer 담당). 
후속 작업: 결과 수정, 부분 재실행, 업데이트, 보완, 다시 구현, 이전 구현 개선 요청 시에도 사용."
---

# Spring Boot Dev Orchestrator

Spring Boot 백엔드 피처 구현을 위해 전문 에이전트 팀을 조율하는 통합 스킬.

## 실행 모드: 하이브리드

| Phase | 모드 | 이유 |
|-------|------|------|
| Phase 1 (분석) | 서브 에이전트 | 단독 코드 탐색, 팀 통신 불필요 |
| Phase 2 (구현) | 에이전트 팀 | 전문 영역 병렬 구현 + 상호 피드백 |
| Phase 3 (QA) | 서브 에이전트 | 독립 검증자가 객관적으로 검수 |

## 에이전트 구성

| 팀원 | 타입 | 역할 | 스킬 |
|------|------|------|------|
| backend-analyst | general-purpose | 코드 탐색, API 계약·DB 스키마·DDD 도메인 설계 | ddd-tactical |
| backend-developer | general-purpose | 비즈니스 로직, 리포트·FCM (전술적 DDD 구현) | ddd-tactical |
| security-developer | general-purpose | JWT, OAuth2, Spring Security, RBAC, Redis RT | spring-security-impl |
| realtime-developer | general-purpose | WebSocket(STOMP) 채팅, ChatRoom·ChatMessage, FCM 오프라인 | websocket-impl |
| infra-developer | general-purpose | 관측성(actuator)·JVM/GC·DB 풀·SQS producer 배선·docker 하드닝·PII 로깅·smoke test | spring-infra |
| qa-reviewer | general-purpose | 코드 리뷰, 테스트 작성, CodeRabbit | spring-qa, coderabbit-review |

## OCR 트리거 경계
- **범위 내 (Spring):** 사고 상황 입력 수신, 진단서 S3 업로드, OCR 트리거 SQS **producer** 발행
- **범위 외 (FastAPI):** OCR 실행·LangGraph·RAG 등 AI 처리, SQS **consumer** 측 내부 처리
> 즉 "S3 저장 + SQS로 OCR 트리거 발행"까지는 이 스킬로 구현하고, 그 이후 AI 파이프라인은 FastAPI 담당이다.

## 워크플로우

### Phase 0: 컨텍스트 확인

1. `_workspace/` 존재 여부 확인 — **레포 루트 기준**이다(`.claude/settings.json`의 `Write(_workspace/**)` 권한도 루트 기준 glob). 이 스킬 파일이 있는 `.claude/skills/springboot-dev/` 밑이 아니다 — 과거 세션이 이 경로를 스킬 디렉터리 기준으로 오해해 `.claude/skills/springboot-dev/_workspace/`에 산출물을 남긴 전례가 있다(정리하지 않고 보존 중 — harness.md §8 "삭제하지 않는다" 원칙).
2. 실행 모드 결정:
   - **미존재** → 초기 실행. Phase 1로 진행
   - **존재 + 부분 수정 요청** → 해당 에이전트만 재호출, 기존 산출물 중 수정 대상만 덮어쓰기
   - **존재 + 새 피처 요청** → 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동 후 Phase 1 진행

### Phase 1: 분석 (서브 에이전트)

1. `_workspace/00_input/request.md` 생성 — 사용자 요청 정리
2. backend-analyst 서브 에이전트 호출:
   ```
   Agent(
     subagent_type: "backend-analyst",
     model: "opus",
     prompt: "request.md를 읽고 design.md를 작성하라. 경로: _workspace/01_analyst/design.md"
   )
   ```
3. `_workspace/01_analyst/design.md` 검토 후 구현 범위 확인
4. 구현에 필요한 전문가 판단:
   - 인증/권한/Redis RT 변경 포함 → security-developer 포함
   - WebSocket(STOMP) 채팅 포함 → realtime-developer 포함
   - 인프라/관측성/배포/JVM·GC/DB 풀/SQS producer 설정·배선/docker·헬스체크/PII 로깅/smoke test → infra-developer 포함
   - 비즈니스 로직만 → backend-developer 단독
   > 순수 인프라 하드닝 요청(피처 아님)은 분석(Phase 1)·QA(Phase 3)를 축약하고 infra-developer 단독 실행 후 컴파일·`docker compose config`로 검증할 수 있다.

### Phase 2: 구현 (에이전트 팀)

**실행 모드:** Agent() 병렬 호출

1. 필요한 에이전트만 선택 후 병렬 실행:
   ```
   // 인증 변경 포함 시 — backend-developer + security-developer 병렬
   Agent(subagent_type: "backend-developer", model: "opus",
     prompt: "_workspace/01_analyst/design.md를 읽고 비즈니스 로직을 구현하라. 완료 후 _workspace/02_backend/summary.md에 변경 파일 목록을 기록하라.")
   Agent(subagent_type: "security-developer", model: "opus",
     prompt: "_workspace/01_analyst/design.md의 권한 섹션을 읽고 인증·인가·Redis RT를 구현하라. 완료 후 _workspace/02_security/summary.md에 변경 파일 목록을 기록하라.")

   // WebSocket 포함 시 — realtime-developer 추가
   Agent(subagent_type: "realtime-developer", model: "opus",
     prompt: "_workspace/01_analyst/design.md를 읽고 WebSocket 채팅 기능을 구현하라. 완료 후 _workspace/02_realtime/summary.md에 변경 파일 목록을 기록하라.")

   // 인프라/관측성/배포 하드닝 포함 시 — infra-developer 추가 (spring-infra 스킬 참조)
   Agent(subagent_type: "infra-developer", model: "opus",
     prompt: "spring-infra 스킬을 참조해 관측성·JVM·DB풀·SQS producer 배선·docker 하드닝을 구현하라. 완료 후 _workspace/02_infra/summary.md에 변경 파일 목록과 설정 값 근거를 기록하라.")

   // 비즈니스 로직만 — backend-developer 단독
   Agent(subagent_type: "backend-developer", model: "opus",
     prompt: "_workspace/01_analyst/design.md를 읽고 비즈니스 로직을 구현하라. 완료 후 _workspace/02_backend/summary.md에 변경 파일 목록을 기록하라.")
   ```

2. 에이전트 간 의존 정보는 design.md에 명시 (SecurityContext userId 추출 방식, FCM 서비스 경로 등)

3. 모든 Agent() 호출이 완료될 때까지 대기 후 summary.md 파일 확인

### Phase 3: QA (서브 에이전트)

1. qa-reviewer 서브 에이전트 호출:
   ```
   Agent(
     subagent_type: "qa-reviewer",
     model: "opus",
     prompt: "_workspace/02_*/summary.md를 읽고 코드 리뷰·테스트 작성·CodeRabbit 리뷰를 수행하라. 결과: _workspace/03_qa/review-report.md"
   )
   ```
2. review-report.md에서 CRITICAL 이슈 확인
3. CRITICAL 이슈 존재 시 Phase 2 해당 에이전트 단독 재호출 (부분 재실행)

### Phase 4: Git 워크플로우 (구현 완료 후 사용자 승인 필요)

구현과 QA가 완료되면 변경 범위, 테스트 결과, 남은 TODO를 먼저 사용자에게 보고한다.
커밋 또는 PR 생성은 사용자가 명시적으로 승인한 경우에만 git-workflow 스킬을 순서대로 실행한다.

1. **승인 요청** — 커밋/PR 진행 여부 확인
   - 변경 파일 목록과 테스트 결과를 요약한다
   - 커밋만 진행할지, 커밋 후 PR까지 생성할지 사용자 확인을 받는다

2. **커밋** — 승인 시 Workflow A 실행
   - 변경 파일 범위 확인 → 필요 시 커밋 분리 제안
   - Conventional Commits 형식으로 커밋
   - Co-Authored-By 자동 추가

3. **PR 생성** — 승인 시 Workflow C 실행
   - base 브랜치 확인 (develop 우선, 없으면 main)
   - 이슈 번호가 있으면 PR 제목에 포함
   - 체크리스트 포함 PR 본문 작성
   - CodeRabbit 리뷰 트리거 확인

### Phase 5: 정리

1. `_workspace/` 보존 (삭제하지 않음)
2. 사용자에게 결과 요약:
   - 구현된 파일 목록
   - QA 결과 요약
   - 생성된 커밋 + PR URL
   - 남은 TODO 목록

## 데이터 흐름

```
request.md → [backend-analyst] → design.md
                                    ↓
   [backend-developer] ←→ [security-developer] ←→ [infra-developer]
           ↓                    ↓                       ↓
   02_backend/summary   02_security/summary     02_infra/summary
                         ↘        ↓        ↙
                          [qa-reviewer]
                                ↓
                        review-report.md
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 팀원 1명 실패 | SendMessage로 상태 확인 → 재시작. 재실패 시 해당 영역 누락 명시하고 진행 |
| CRITICAL 이슈 발견 | qa-reviewer가 리더에게 알림 → 해당 에이전트 단독 재호출 |
| 설계 모호 | backend-analyst에게 보완 요청 (부분 재실행) |

## 테스트 시나리오

### 정상 흐름
1. "손해사정사 매칭 요청 API 구현해줘" 요청
2. backend-analyst가 design.md 생성 (매칭 엔티티, API 계약, ADJUSTER 권한)
3. backend-developer + security-developer 팀 구성, 작업 병렬 진행
4. qa-reviewer가 테스트 작성 + CodeRabbit 리뷰 반영
5. `_workspace/03_qa/review-report.md` 생성

### 에러 흐름
1. security-developer가 Redis 연결 오류로 중지
2. 리더가 유휴 알림 수신 → SendMessage로 Embedded Redis 사용 지시
3. 재시작 성공
4. 최종 리포트에 "Redis 로컬 테스트는 Embedded Redis 사용" 명시
