---
name: ddd-tactical
description: "Spring Boot 전술적 DDD(Tactical DDD) 구현 가이드. 실용적 레이어드 패키지(domain/<context>/{controller,dto,entity,repository,service})에서 Aggregate·Entity·Value Object·리치 도메인 모델·도메인 이벤트를 어떻게 챙기는지 다룬다. Repository(Spring Data JPA), Service 트랜잭션 경계, DTO 매핑, 안티패턴을 포함. 새 도메인/엔티티/유스케이스 설계·구현, 패키지 구조 결정, '애그리거트', 'VO', '도메인 모델', 'DDD 구조' 관련 작업 시 반드시 이 스킬을 참조. backend-analyst(설계)·backend-developer(구현)가 공유."
---

# Spring Boot 전술적 DDD 구현 가이드

이 프로젝트는 **전술적 DDD**를 지향하되, 패키지는 **실용적 레이어드 구조**로 둔다. 핵심은 "비즈니스 규칙(불변식)을 `entity` 안에 두고, 프레임워크·인프라를 바깥으로 밀어내는 것"이다. **폴더를 4계층으로 쪼개지 않아도** 리치 도메인 모델·VO·불변식만 지키면 전술 DDD의 이점은 그대로 얻는다. backend-analyst는 이 가이드로 Aggregate 경계와 유스케이스를 설계하고, backend-developer는 이 구조로 구현한다.

## 목차
1. 패키지 구조 (Bounded Context × 5 레이어)
2. 레이어 의존 규칙
3. 전술 패턴: Aggregate / Entity / VO
4. Repository (Spring Data JPA)
5. Service (트랜잭션 경계)
6. Controller · DTO 매핑
7. 도메인 이벤트
8. 안티패턴
9. 프로젝트 제약과의 정합

## 1. 패키지 구조

`domain/` 아래 컨텍스트(도메인)를 최상위로, 그 안을 5개 레이어로 나눈다.

```
com.soma.backend.domain.match
├── controller/    MatchController                          — REST, 얇게
├── dto/           MatchRequestRequest, MatchResponse       — API 계약(snake_case)
├── entity/        MatchRequest(Aggregate Root), MatchStatus(enum), Money(VO)
├── repository/    MatchRequestRepository (Spring Data JPA)
└── service/       MatchService                             — 유스케이스 + @Transactional
```

- 한 컨텍스트 = `domain/` 아래 한 패키지. 컨텍스트 간 직접 참조는 피하고, 꼭 필요하면 service 레이어에서 조합하거나 도메인 이벤트로 연결한다.
- `global/`(config·exception·security), `infra/`(redis·s3·fcm·sqs·outbox)는 전역 공유로 유지한다.
- 엔티티가 많아지면 `entity/` 아래 하위 패키지(`entity/vo`)로 나눠도 되지만, 처음엔 평평하게 둔다.

## 2. 레이어 의존 규칙

의존은 **항상 안쪽(entity)으로**. 안은 바깥을 모른다.

| 레이어 | 의존 가능 | 금지 |
|--------|-----------|------|
| controller | service, dto, (조회용) entity | repository 직접 호출, JPA 엔티티를 Response로 노출 |
| service | entity, repository, dto | controller |
| repository | entity | service, controller |
| entity | (JPA 애노테이션까지만) | Spring Web, service, controller, repository |

컴파일러가 강제하진 않으므로 리뷰(qa-reviewer)와 import 점검으로 지킨다. `entity`가 `org.springframework.web`을 import하거나 `controller`가 `repository`를 직접 주입하면 위반이다.

## 3. Aggregate / Entity / Value Object

**실용 모드 (기본):** JPA 엔티티가 곧 Aggregate Root다. 애노테이션을 엔티티에 허용하되, **비즈니스 로직을 엔티티 안에** 둔다(setter 남발 금지). 순수 분리(도메인 POJO + 별도 JPA 엔티티 + 매퍼)는 불변식이 복잡한 소수 Aggregate에만 선택 적용한다.

```java
// entity/MatchRequest.java — Aggregate Root
@Entity
@Table(name = "match_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MatchRequest {

    @Id
    private UUID id;                       // PK는 UUID (프로젝트 규약)

    @Column(nullable = false)
    private UUID userId;                   // 다른 Aggregate(User)는 ID로만 참조

    @Column(nullable = false)
    private UUID adjusterId;

    @Enumerated(EnumType.STRING)
    private MatchStatus status;            // VO(enum)

    // 정적 팩터리 — 생성 규칙을 한곳에
    public static MatchRequest create(UUID userId, UUID adjusterId) {
        MatchRequest request = new MatchRequest();
        request.id = UUID.randomUUID();
        request.userId = userId;
        request.adjusterId = adjusterId;
        request.status = MatchStatus.CONNECTED;   // 즉시 연결 (수락 단계 없음)
        return request;
    }

    // 비즈니스 규칙은 엔티티 안에서 상태를 바꾼다 (불변식 보호)
    public void cancel() {
        if (this.status == MatchStatus.CANCELED) {
            throw new BusinessException(ErrorCode.MATCH_ALREADY_CANCELED);
        }
        this.status = MatchStatus.CANCELED;
    }
}
```

**Value Object** — 식별자 없는 불변 값은 `record`로. 동등성은 값으로 판단된다. `entity` 패키지에 함께 둔다.

```java
// entity/Money.java
public record Money(long amount, String currency) {
    public Money {
        if (amount < 0) {
            throw new BusinessException(ErrorCode.INVALID_AMOUNT);
        }
    }
    public Money add(Money other) {
        return new Money(this.amount + other.amount, this.currency);
    }
}
```

- Aggregate는 **불변식 경계**다. 외부는 Root 메서드를 통해서만 내부를 바꾼다.
- Aggregate 간 참조는 **객체가 아니라 ID(UUID)**로. `@ManyToOne`으로 다른 Aggregate를 물지 않는다(한 Aggregate가 비대해지고 경계가 무너진다).
- "한 엔티티에 넣기 애매한, 여러 Aggregate에 걸친 규칙"은 별도 도메인 서비스 클래스(예: `service/MatchingPolicy`)로 빼되, 상태 없는 순수 규칙으로 둔다. 대부분의 규칙은 엔티티 메서드로 충분하다.

## 4. Repository (Spring Data JPA)

인터페이스 하나로 충분하다. 포트/어댑터 분리는 하지 않는다(실용 모드).

```java
// repository/MatchRequestRepository.java
public interface MatchRequestRepository extends JpaRepository<MatchRequest, UUID> {

    Optional<MatchRequest> findByUserIdAndStatus(UUID userId, MatchStatus status);
}
```

- Repository는 **Aggregate Root 단위로만** 저장/조회한다. 내부 Entity를 따로 조회하지 않는다.
- 복잡한 조회(목록·검색·통계)는 조회 전용 메서드로 분리하고, 필요하면 `dto`의 Response(또는 projection)를 직접 반환하는 QueryService/조회 메서드로 뺀다. 쓰기 경로만 Aggregate/Repository를 거친다.
- **조회 쿼리는 native query 금지.** 동적 조회(필터·정렬·페이지네이션·서브쿼리)는 QueryDSL로 작성한다 — `*RepositoryCustom` 인터페이스 + `*RepositoryImpl`(생성자 주입 `JPAQueryFactory`) 프래그먼트를 두고 `JpaRepository`와 함께 확장한다. 매핑되지 않은 연관은 엔티티 조인(`.join(q).on(...)`)으로, projection은 인터페이스 대신 record + `Projections.constructor`로 받는다. 단순 조회·카운트는 Spring Data 파생 쿼리나 JPQL(`@Query`)로 충분하다. 아직 엔티티로 매핑되지 않은 테이블을 조인하는 읽기 전용 projection처럼 QueryDSL/JPQL로 표현할 수 없는 경우에 한해, 리포지토리에 사유를 주석으로 남긴 **문서화된 예외**로만 native(`nativeQuery = true`)를 허용한다.

### 4-1. 다른 팀이 소유한 테이블을 읽기 전용으로 매핑하기 (`@Subselect`)

다른 컨텍스트/팀이 스키마를 소유한 테이블(예: `ai.ocr_job_failures` — AI 워커 소유, Backend는 SELECT만)을 읽어야 할 때 보통의 `@Table(schema=...)` 매핑을 쓰지 말 것. `ddl-auto: validate`가 부팅 시 그 테이블의 메타데이터를 검증하는데, 소유 팀의 GRANT·마이그레이션이 아직 없으면 **Backend 앱 부팅 자체가 실패**한다 — 남의 배포 일정에 우리 가용성이 묶이는 셈이다.

대신 Hibernate `@Immutable` + `@Subselect`로 매핑한다:

```java
@Entity
@Immutable
@Subselect("select id, report_id, failure_class, terminal from ai.ocr_job_failures")
public class OcrJobFailureView { ... }
```

`@Subselect`는 `isPhysicalTable()`이 false라 스키마 검증·DDL 생성 대상에서 빠진다 — 부팅은 항상 성공하고, 권한 미부여·테이블 부재의 장애 반경이 그 뷰를 읽는 조회 하나로 격리된다. QueryDSL Q타입도 정상 생성된다(`@Entity`만 보고 만드므로 `@Subselect` 여부와 무관) — native 예외가 필요 없다. 리포지토리는 `JpaRepository` 대신 `Repository<T, ID>`를 확장해 `save`/`delete`가 타입 레벨에 아예 없게 하면 이중 방어가 된다. 소유 테이블에 Flyway `CREATE`/`ALTER`/`GRANT`를 넣지 않는다 — 이름 해석과 권한은 별개 문제이고, GRANT는 객체 소유자만 실행할 수 있어 Backend Flyway 롤로는 실행 자체가 실패한다.

## 5. Service (트랜잭션 경계)

유스케이스 한 개 = 메서드 한 개. **`@Transactional`은 여기에만** 둔다(`open-in-view: false`라 응답 전에 트랜잭션이 끝나야 한다).

```java
// service/MatchService.java
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchRequestRepository matchRequestRepository;
    private final ChatRoomService chatRoomService;           // 다른 컨텍스트 협력
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public MatchResponse requestMatch(UUID userId, MatchRequestRequest request) {
        MatchRequest match = MatchRequest.create(userId, request.adjusterId());
        matchRequestRepository.save(match);
        chatRoomService.createRoom(userId, request.adjusterId());   // 즉시 연결
        eventPublisher.publishEvent(new MatchAcceptedEvent(match.getId()));
        return MatchResponse.from(match);
    }
}
```

- 조회 메서드는 `@Transactional(readOnly = true)`.
- 서비스는 **얇게** — 흐름(조회→도메인 메서드 호출→저장)만 조율하고, 규칙 판단은 엔티티에 위임한다. `if`로 상태를 검사해서 값을 바꾸고 있다면 그 로직은 엔티티로 내려야 한다는 신호다(anemic domain 경고).
- 컨텍스트 간 협력은 상대 컨텍스트의 `service`를 주입해 쓰되, 순환 의존이 생기면 도메인 이벤트로 끊는다.
- **다른 팀 소유 테이블 조회를 `try/catch`로 degrade시킬 땐 `REQUIRES_NEW`가 필수다.** 호출자의 `@Transactional` 메서드 안에서 그 조회가 `DataAccessException`을 던지고 그걸 그 자리에서 잡아도, Hibernate는 이미 세션을 rollback-only로 표시해버려 커밋 시점에 `UnexpectedRollbackException`이 다시 터진다(degrade가 무의미해짐). 격리하려면 그 조회 메서드에 `@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)`를 붙이고 호출자가 그 메서드 호출만 `catch (DataAccessException)`한다. 대가는 있다 — Spring이 `REQUIRES_NEW` 진입 시 바깥 트랜잭션의 커넥션을 반납하지 않고 쥔 채로 새 커넥션을 하나 더 받으므로, **요청 하나가 순간적으로 HikariCP 커넥션 2개를 점유**한다. 이 패턴을 쓸 때마다 풀 크기(`spring.datasource.hikari.maximum-pool-size`)를 다시 검토할 것(spring-infra 스킬 §3 실제 사례 참고).

### REQUIRES_NEW로 실패를 격리할 때 흔한 함정

다른 팀 소유 스키마 조회(`ai.*`)처럼 실패할 수 있는 조회를 `@Transactional(propagation = REQUIRES_NEW)`로 감싸는 이유는 그 실패가 호출자 트랜잭션을 오염시키지 않게 하기 위해서다. 아래 둘을 지키지 않으면 격리가 무의미해진다(NEEDS_REUPLOAD degrade 버그, PR #251에서 실제로 겪음).

1. **같은 클래스 안에서 자가 호출(self-invocation)하면 `@Transactional`이 안 걸린다.** Spring AOP 프록시는 외부에서 들어오는 호출만 가로채므로, `this.foo()`로 부르면 새 트랜잭션이 열리지 않고 호출자의 트랜잭션을 그대로 쓴다. 격리하려면 **별도 Bean**(`@Component`/`@Service`)으로 분리해 주입해서 호출해야 한다(`ReportHoldInitializer`·`TerminalFailureJournalReader` 참고).
2. **격리된 트랜잭션 안에서 예외를 삼키면 안 된다.** PostgreSQL은 트랜잭션 안에서 문장 하나가 실패하면 커밋·롤백 전까지 이후 모든 문장이 "current transaction is aborted"로 연쇄 실패한다. `REQUIRES_NEW` 메서드 **안에서** try/catch로 예외를 삼키면, Spring은 메서드가 정상 종료했다고 보고 그 트랜잭션을 커밋하려 드는데 DB 세션은 이미 aborted 상태라 커밋 자체가 실패한다. 예외는 메서드 밖으로 그대로 던져 Spring이 트랜잭션을 롤백하게 하고, **호출자가 그 호출 지점에서(자기 트랜잭션 안에서) 잡아야** 한다.

## 6. Controller · DTO 매핑

컨트롤러는 얇게. HTTP ↔ 유스케이스 변환만 한다.

```java
// controller/MatchController.java
@RestController
@RequestMapping("/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    @PostMapping
    public ResponseEntity<ApiResponse<MatchResponse>> requestMatch(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody MatchRequestRequest request) {

        MatchResponse response = matchService.requestMatch(principal.getUserId(), request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
```

```java
// dto/MatchRequestRequest.java  (Request)
public record MatchRequestRequest(@NotNull UUID adjusterId) { }

// dto/MatchResponse.java  (Response) — 엔티티 → Response 매핑을 여기에
public record MatchResponse(UUID id, String status) {
    public static MatchResponse from(MatchRequest match) {
        return new MatchResponse(match.getId(), match.getStatus().name());
    }
}
```

- Request/Response DTO는 `dto` 패키지. 필드는 snake_case(Jackson 전역), 검증은 `@Valid`.
- **JPA 엔티티를 컨트롤러 밖(Response)으로 노출하지 않는다.** 매핑은 Response의 `from()` 정적 메서드에 둔다.
- 매핑 방향: Request → (service에서 entity) → Response.

## 7. 도메인 이벤트

여러 Aggregate/컨텍스트를 한 트랜잭션에 묶지 않기 위해 이벤트로 결합을 끊는다. 이벤트 record는 `entity`(또는 `entity/event`)에, 리스너는 `service`에 둔다.

```java
// entity/MatchAcceptedEvent.java
public record MatchAcceptedEvent(UUID matchRequestId) { }

// service/MatchAcceptedListener.java
@Component
class MatchAcceptedListener {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(MatchAcceptedEvent event) {
        // FCM 발송 등 부수효과 — 커밋 후 실행, 메인 트랜잭션과 분리
    }
}
```

- 커밋 후 부수효과(FCM 푸시, SQS 발행 등)는 `AFTER_COMMIT` 리스너로. 실패해도 메인 트랜잭션은 이미 커밋됨.

## 8. 안티패턴 (리뷰에서 잡을 것)

| 안티패턴 | 문제 | 교정 |
|----------|------|------|
| 빈약한 도메인(Anemic Domain) | 엔티티가 getter/setter뿐, 로직은 Service에 | 비즈니스 규칙을 엔티티 메서드로 이동 |
| Aggregate 간 `@ManyToOne` 직접 참조 | 경계 붕괴, N+1, 거대 그래프 | ID(UUID) 참조로 전환 |
| `@Transactional`을 엔티티/컨트롤러에 | 경계 모호, open-in-view 위반 | service에만 |
| JPA 엔티티를 Response로 반환 | 내부 노출, 지연로딩 직렬화 오류 | dto의 Response로 매핑 |
| entity가 Spring Web/service import | 의존 방향 역전 | 엔티티는 순수하게(JPA 애노테이션까지만) |
| controller가 repository 직접 주입 | 레이어 건너뜀, 트랜잭션 경계 밖 조회 | 반드시 service 경유 |
| Repository가 내부 Entity 단위 저장 | Aggregate 불변식 우회 | Root 단위 저장 |

## 9. 프로젝트 제약과의 정합

- **PK UUID** · **`ddl-auto: validate` + Flyway**: 엔티티 매핑은 마이그레이션 스키마와 정확히 일치.
- **`open-in-view: false`**: 지연로딩은 service 트랜잭션 안에서 초기화. Response 매핑은 트랜잭션 종료 전에 끝내거나 필요한 데이터를 Response에 담아 나온다.
- **응답 포맷**: 성공 `ApiResponse.ok(...)`, 실패는 `BusinessException(ErrorCode)` → `GlobalExceptionHandler`. 도메인 규칙 위반도 `BusinessException`으로 던진다.
- **snake_case**: dto의 Request/Response 필드에 적용(Jackson 전역).
- **OCR 트리거 경계**: 사고 입력 수신·S3 업로드·SQS producer 발행은 report 컨텍스트의 service(+ infra 공유)에 위치. OCR 실행·consumer는 FastAPI(범위 외).
