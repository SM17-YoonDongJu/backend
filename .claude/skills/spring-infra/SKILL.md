---
name: spring-infra
description: "Spring Boot 인프라·관측성·배포 하드닝 구현 가이드. Actuator 헬스체크·liveness/readiness 프로브, JVM 메모리 제한(MaxRAMPercentage)·GC 로그·OOM 힙덤프, HikariCP 커넥션 풀 제한, SQS producer 배선(SqsClient·아웃박스 릴레이)과 로컬 LocalStack docker compose, Dockerfile·docker-compose 하드닝(restart·healthcheck·mem_limit), PII-안전 로깅(logback), curl·k6 smoke test 작성 시 반드시 이 스킬을 참조. '헬스체크 열기', 'JVM 메모리 제한', 'GC 로그', 'DB 풀 제한', 'SQS producer 설정', 'docker healthcheck', '프로덕션 하드닝', '관측성 세팅', 'smoke test' 요청과 이들의 '다시/수정/보완/추가' 후속 요청 시 사용. 비즈니스 로직 구현은 제외(springboot-dev 사용)."
---

# Spring Boot 인프라·관측성·배포 하드닝 가이드

infra-developer 에이전트가 참조하는 운영 준비(production readiness) 구현 가이드. 비즈니스 로직이 아니라 앱이 안정적으로 뜨고·관측되고·배포되는 **설정과 배선**을 다룬다.

이 프로젝트의 제약을 지킨다: `open-in-view: false`, `ddl-auto: validate`, Flyway, snake_case, UUID PK, 응답 포맷(`ApiResponse`). 설정 값에는 **왜 그 값인지** 근거 주석을 남긴다.

## 대상 파일
| 영역 | 파일 |
|------|------|
| 앱 설정(풀·producer·actuator·로깅) | `src/main/resources/application.yml` |
| 로깅 상세 | `src/main/resources/logback-spring.xml` |
| JVM·컨테이너 | `Dockerfile` |
| 서비스 오케스트레이션 | `docker-compose.yml` |
| 의존성 | `build.gradle` |
| 환경 문서 | `.env.example` |
| 정책 문서 | `docs/logging-pii-policy.md` *(생성 예정 — 아직 부재)* |
| 검증 | `scripts/smoke-test.sh`, `scripts/smoke-test.k6.js` |

## 1. Actuator 헬스체크 / 프로브

의존성: `implementation 'org.springframework.boot:spring-boot-starter-actuator'`

**현재 `application.yml` 상태(하드닝 미완):**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # metrics·prometheus까지 노출 — 하드닝 TODO
  endpoint:
    health:
      show-details: always    # 상세 무조건 노출 — 하드닝 TODO(PII·인프라 정보). 권장: when-authorized 또는 never
      probes:
        enabled: true          # liveness/readiness 프로브 → /actuator/health/{liveness,readiness}
```

- **⚠ 하드닝 TODO(민감 노출):** 현재는 `metrics,prometheus`가 열려 있고 `show-details: always`다. `metrics`/`prometheus`는 인증 뒤로 두거나(아래 SecurityConfig 참고) 스크레이프 전용 경로로 좁히고, `show-details`는 외부 노출 환경에선 `never`/`when-authorized`로 조인다. `env`·`configprops`·`heapdump`는 `include`에 없어 여전히 차단됨(넓히지 말 것).
- **SecurityConfig 확인:** 실제 `SecurityConfig`는 `.anyRequest().authenticated()`이고 permitAll은 `/actuator/health`·`/actuator/health/**`만이다(`/auth/**`·`/ws/**`·`/ws-chat/**` 포함). 즉 **`/actuator/info,metrics,prometheus`는 인증이 필요**하다 — 대시보드·Prometheus 스크레이퍼에서 붙이려면 별도 인가(예: 내부망 IP·ADMIN 롤·전용 자격증명)를 security-developer와 배선한다.
- **readiness 그룹:** 기본은 `readinessState`만 포함 → DB 다운이 readiness를 죽이지 않는다. DB 장애 시 트래픽 차단(무중단 배포)을 원하면 `management.endpoint.health.group.readiness.include: readinessState,db` 추가.

## 2. JVM 메모리 제한 / GC 로그 (Dockerfile)

컨테이너에서는 고정 `-Xmx`가 아니라 **`MaxRAMPercentage`**로 컨테이너 메모리 한계를 인식하게 한다. `JAVA_OPTS`로 오버라이드 가능하게 배선한다.

```dockerfile
RUN mkdir -p /app/logs
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 \
-XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError \
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heapdump.hprof \
-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
```

- **`exec`**를 쓰는 이유: java가 PID 1이 되어 SIGTERM(graceful shutdown)을 직접 받는다. `sh -c "java ..."` 없이 exec 없으면 시그널이 java에 전달 안 됨.
- **compose `mem_limit`와 정합:** `mem_limit: 1g` × `MaxRAMPercentage=75%` ≈ 힙 768m. mem_limit를 바꾸면 힙이 자동으로 따라간다.
- **GC 로그**는 회전(`filecount`/`filesize`)으로 디스크 폭주 방지. 힙덤프는 OOM 시 1회 저장 → 사후 분석용.
- **`application.yml`**: `server.shutdown: graceful` 추가로 진행 중 요청 처리 후 종료.

## 3. HikariCP 커넥션 풀 제한 (application.yml)

**현재 `application.yml` 상태(적용된 것만):**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 15
      minimum-idle: 5
      connection-timeout: 30000   # 커넥션 대기 30초 초과 시 예외
```

- **10 → 15로 올린 이유(실제 사례):** `ReportAnalysisStatusQueryService.resolveAll`이 `REQUIRES_NEW`로 별도 트랜잭션을 쓴다 — 호출자(목록/상세 조회)의 트랜잭션 안에서 예외를 잡으면 Hibernate가 세션을 rollback-only로 표시해 커밋 시 `UnexpectedRollbackException`이 다시 터지기 때문에(degrade 로직이 성립하려면 REQUIRES_NEW가 필수) 격리를 택했다. 그런데 Spring이 `REQUIRES_NEW` 진입 시 바깥 트랜잭션의 커넥션을 반납하지 않고 그대로 쥔 채 새 커넥션을 하나 더 받으므로, **요청 하나가 순간적으로 커넥션 2개를 점유**한다. 동시 요청이 몰리면 실효 동시성이 절반으로 줄어 10에서는 `connection-timeout`이 날 수 있어 여유를 뒀다(design.md `feat/ocr-failure-status` §14 I4). **같은 함정을 피하려면:** `REQUIRES_NEW`를 캐치-앤-디그레이드 용도로 쓸 때마다 풀 크기를 이 배율만큼 다시 계산할 것 — 무조건 늘리기보다, 가능하면 그 조회를 호출자의 트랜잭션 밖(비-트랜잭션 컨텍스트)에서 실행해 애초에 이중 점유를 피하는 구조도 함께 검토한다(이번엔 `open-in-view: false`상 DTO 조립이 트랜잭션 안에서 끝나야 해서 구조 변경 대신 풀 크기 증설을 택했다).

**하드닝 TODO(아직 미적용 — 권장 추가값):**
```yaml
      pool-name: ${DB_POOL_NAME:soma-hikari}          # 로그·모니터링 식별
      maximum-pool-size: ${DB_POOL_MAX_SIZE:10}       # env 오버라이드
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      idle-timeout: 600000        # 유휴 10분 후 회수
      max-lifetime: 1740000       # 최대 수명 29분 — DB/LB 타임아웃보다 짧게
      keepalive-time: 300000
```

- **풀 크기 근거:** 무한정 늘리면 DB가 죽는다. `maximum-pool-size`는 DB `max_connections`와 인스턴스 수를 역산해서 정한다(기본 10은 단일 인스턴스 보수값). 하드닝 시 env로 환경별 오버라이드를 배선한다.
- **`max-lifetime`은 DB/LB 유휴 종료 시간보다 짧게** — 그래야 죽은 커넥션을 쥐고 있다가 터지는 걸 막는다. 현재는 미설정(Hikari 기본 30분)이라 명시 설정을 권장한다.
- **현재 배포 토폴로지: 단일 인스턴스.** dev·prod `docker-compose.*.yml` 모두 `backend` 서비스가 1개고 `replicas`/`deploy.replicas` 설정이 없다(k8s 아님). `@Scheduled` 스윕러(`BlockedReportNotificationSweeper` 등)가 행 잠금·`@Version` 없이도 "동시 실행 시 중복 처리" 위험을 감수하는 근거가 이것이다 — 다중 인스턴스로 스케일아웃하면 이 가정이 깨지므로, 그때는 스윕러 전반에 분산 락 또는 `@Version` 낙관적 잠금을 재검토해야 한다(PR #251 CodeRabbit 리뷰에서 지적됨).

## 4. SQS producer 배선 + 로컬 브로커(LocalStack)

의존성: `implementation 'software.amazon.awssdk:sqs:2.26.0'`(S3와 같은 AWS SDK v2 라인, spring-cloud-aws 미사용). **consumer는 report 워커(Python) 담당이라 producer만 설정.**

> **실제 배선:** OCR 트리거는 트랜잭셔널 아웃박스로 적재하고(`OcrJobOutboxPortImpl` → `kafka_outbox_events`),
> `OutboxRelay`가 폴링해 **`SqsClient.sendMessage`** 로 발행한다. `SqsClient` 빈은 `infra/sqs/SqsConfig`가
> **S3Config와 동일 패턴**으로 구성한다 — `aws.region` + `DefaultCredentialsProvider`(IAM Role 위임, 정적 키 미주입).
> `aws.sqs.endpoint`가 있으면(로컬 LocalStack) `endpointOverride` + 더미 StaticCredentials로 로컬에 붙는다.
> 발행 대상 큐는 아웃박스 `topic` 컬럼(=SQS 큐 이름)이며, 큐 URL은 `GetQueueUrl`로 1회 해석해 캐시한다.
> 큐 이름은 고정값이 아니라 `app.sqs.ocr-queue-name`(`${SQS_OCR_QUEUE_NAME:ocr-job-queue}`)으로 **환경별 주입**한다 —
> 로컬/test는 기본값 `ocr-job-queue`, dev·prod는 `.env`의 `SQS_OCR_QUEUE_NAME`(`brbs-ocr-job-queue-{env}`)이다.
> **엔티티는 `OcrOutboxEvent`, 테이블은 `kafka_outbox_events`로 이름이 다르다** — 전송 계층을 SQS로 교체하면서
> 클래스만 `OcrOutbox*`(`OcrOutboxEvent`·`OcrOutboxStatus`·`OcrOutboxRepository`)로 정리했고, 테이블 리네임은
> **보류**했다. `ALTER TABLE ... RENAME TO`가 `public` 스키마 `CREATE` 권한을 요구하는데 운영 DB 유저에 그 권한이
> 없어 마이그레이션이 실패하기 때문이다. 문서·쿼리에서 둘을 섞어 쓰지 말 것(테이블명은 `kafka_outbox_events` 그대로).

SQS 안전값은 브로커 위치와 무관하게 유효하다:
- **at-least-once + 수신측 멱등:** SQS 표준 큐는 최소 1회 전달이라 중복 가능 — 수신자(report 워커)가 `job_id`로 멱등 처리한다(아웃박스도 at-least-once).
- **큐 타입 Standard:** OCR 잡은 독립적·순서 무관이라 표준 큐를 쓴다(FIFO 불필요). 발행 재시도는 아웃박스 `attempts`(MAX 5) 초과 시 FAILED로 파킹.
- **DLQ는 큐 redrive policy로:** 앱 코드가 아니라 큐 설정(maxReceiveCount → DLQ)이며 프로비저닝(IaC) 몫이다.
- **apiCallTimeout:** `SqsConfig`가 5s로 걸어 릴레이 폴러 스레드의 SKIP LOCKED 락 점유를 짧게 묶는다(relay tx timeout 30s가 백스톱).

```java
// infra/sqs/SqsConfig.java — 핵심만
SqsClient.builder()
    .region(Region.of(region))
    .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(5)))
    // endpoint 있으면 LocalStack(더미 크리덴셜), 없으면 DefaultCredentialsProvider(IAM Role)
    .credentialsProvider(hasEndpoint ? dummyStatic : DefaultCredentialsProvider.create())
    .build();
```

로컬 브로커는 **LocalStack(SQS)**를 compose에 올리고, ready 훅(`deploy/localstack/init-sqs.sh`)이 `ocr-job-queue`를 생성한다. 운영/스테이징은 **관리형 AWS SQS**(컨테이너 없음)로 IAM Role로 접속한다 — `AWS_SQS_ENDPOINT`를 비우면 실제 SQS로 붙는다.

```yaml
  localstack:
    image: localstack/localstack:3
    ports:
      - "${LOCALSTACK_PORT:-4566}:4566"
    environment:
      SERVICES: sqs
    volumes:
      - ./deploy/localstack/init-sqs.sh:/etc/localstack/init/ready.d/init-sqs.sh
    healthcheck:
      test: ["CMD-SHELL", "awslocal sqs get-queue-url --queue-name ocr-job-queue >/dev/null 2>&1 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 15s
```

- app 서비스에 `AWS_SQS_ENDPOINT: http://localstack:4566` + `AWS_REGION` + `depends_on: localstack(healthy)` 추가(호스트 bootRun은 `application-local.yml`이 `localhost:4566` 기본).
- **IAM 권한:** app 태스크/인스턴스 Role = `sqs:SendMessage` + `sqs:GetQueueUrl`. 워커 Role = `sqs:ReceiveMessage`·`DeleteMessage`·`GetQueueAttributes`.
- **`app.outbox.enabled=false`면 릴레이 no-op**(테스트·SQS 미가용 로컬). `@EnableScheduling`은 유지해 다른 스케줄러(OutboxProcessor)를 보존한다.

## 5. PII-안전 로깅

application.yml에서 SQL 바인드·본문 로깅을 억제하고, `logback-spring.xml`로 이중 안전장치를 건다. `spring.jpa.show-sql`은 **쓰지 않는다**(바인드 값 노출).

```yaml
logging:
  level:
    org.hibernate.SQL: warn
    org.hibernate.orm.jdbc.bind: warn        # 바인드 파라미터(=실제 값) 억제
    org.hibernate.type.descriptor.sql: warn
    org.springframework.web.filter.CommonsRequestLoggingFilter: warn
```

`logback-spring.xml`은 body/header를 포함하지 않는 표준 패턴 + 회전 파일 appender로 구성한다. 손해사정 도메인은 주민번호·진단서·결제정보를 다루므로 정책 문서(`docs/logging-pii-policy.md`, **아직 미생성 — 생성 예정**)를 함께 유지한다: 금지 항목(토큰·주민번호·의료·금융·요청 본문), 마스킹 규칙, DTO 통째 로깅 금지.

## 6. Docker restart / healthcheck (docker-compose app)

```yaml
    restart: unless-stopped
    mem_limit: 1g            # JAVA_OPTS MaxRAMPercentage=75% 기준
    volumes:
      - ./logs:/app/logs     # GC 로그·힙덤프 호스트 보존
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 40s      # 부팅 대기 — 부팅 중 unhealthy 오탐 방지
```

- **`restart: unless-stopped`** — 크래시 시 자동 복구하되 사용자가 명시적으로 멈추면 유지. `on-failure`는 수동 재시작을 못 살린다.
- healthcheck는 **readiness** 프로브를 본다(liveness는 살아만 있으면 UP). alpine JRE의 busybox `wget` 사용.
- `.gitignore`에 `logs/`, `*.hprof`, `*.log` 추가.

## 7. Smoke test (배포 후 검증)

`scripts/smoke-test.sh`(curl)는 health/liveness/readiness 상태코드 + 본문 `UP`을 확인한다. `scripts/smoke-test.k6.js`(k6)는 최소 부하로 실패율·p95 threshold를 검증한다. 엔드포인트가 늘면 스크립트에 추가한다. 스모크=배포 후 헬스 확인, 기능 검증은 통합 테스트(spring-qa) 몫 — 경계를 지킨다.

## 검증 절차 (구현 후)
1. `./gradlew compileJava` — 의존성/컴파일 확인
2. `DB_PASSWORD=x docker compose config >/dev/null` — compose 문법 검증(엔진 불필요)
3. Docker 가동 시: `docker compose up -d` → `docker compose --profile app up -d` → `BASE_URL=http://localhost:8080 ./scripts/smoke-test.sh`
4. Docker 미가동 시 라이브 스모크는 건너뛰고 summary에 명시

## summary.md 출력 형식
`_workspace/02_infra/summary.md`에 기록:
- 변경 파일 목록
- 각 설정 값과 **근거**(왜 이 값인지)
- 검증 결과(컴파일/compose config 통과 여부, 라이브 스모크 실행/미실행)
- 남은 TODO (예: SQS 큐·DLQ 프로비저닝·IAM 정책은 IaC 몫; 발행 컴포넌트 OutboxRelay·SqsConfig는 구현됨)
