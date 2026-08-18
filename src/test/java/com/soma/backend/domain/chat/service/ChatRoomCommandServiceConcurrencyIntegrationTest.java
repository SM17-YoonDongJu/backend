package com.soma.backend.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.infra.redis.ChatEventPublisher;

/**
 * 같은 제안으로 상담방을 동시에 최초 개설하는 레이스의 실제 test_db 통합 테스트(CodeRabbit 지적, PR #249).
 *
 * <p>{@code openConsultationRoom}은 "이미 있으면 그대로 반환"하는 멱등 연산이라고 계약돼 있는데, 선조회를
 * 둘 다 통과한 동시 요청에서 INSERT를 격리하지 않으면 진 쪽이 UNIQUE 위반으로 409 DUPLICATE_RESOURCE를
 * 받는다. 격리(REQUIRES_NEW) 이후에는 진 쪽도 승자의 방을 그대로 돌려받아야 한다.
 *
 * <p>{@code @Transactional}을 두지 않는다 — 두 스레드가 서로의 커밋을 관찰해야 하는 테스트라 각 요청이
 * 실제로 커밋돼야 한다. 커밋한 데이터는 {@code @AfterEach}에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("상담방 최초 개설 동시성 통합 테스트")
class ChatRoomCommandServiceConcurrencyIntegrationTest {

  private static final int TIMEOUT_SECONDS = 30;

  /**
   * test 프로파일은 Flyway를 끄고 Hibernate {@code create-drop}으로 스키마를 만든다 — 엔티티에 선언되지 않은
   * V44의 부분 UNIQUE 인덱스가 없어 레이스의 최종 방어선이 빠진다. 운영과 같은 조건에서 검증하려고 이
   * 테스트에서만 같은 정의로 만들었다가 정리한다.
   */
  private static final String CREATE_UNIQUE_INDEX =
      "CREATE UNIQUE INDEX IF NOT EXISTS uk_chatroom_report_review_id "
          + "ON chatroom (report_review_id) WHERE report_review_id IS NOT NULL";
  private static final String DROP_UNIQUE_INDEX = "DROP INDEX IF EXISTS uk_chatroom_report_review_id";

  @Autowired
  private ChatRoomCommandService chatRoomCommandService;
  @Autowired
  private UserRepository userRepository;
  @Autowired
  private ReportRepository reportRepository;
  @Autowired
  private ReportReviewRepository reportReviewRepository;
  @Autowired
  private ChatRoomRepository chatRoomRepository;
  @Autowired
  private ChatMessageRepository chatMessageRepository;
  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** Redis I/O를 테스트에서 끊는다 — 실제 커밋 경로라 afterCommit 브로드캐스트가 진짜로 발화한다. */
  @MockitoBean
  private ChatEventPublisher chatEventPublisher;

  private UUID customerId;
  private UUID adjusterId;
  private UUID reportId;
  private UUID reviewId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(CREATE_UNIQUE_INDEX);

    User customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    User adjuster = userRepository.save(
        User.create("사정사", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    customerId = customer.getId();
    adjusterId = adjuster.getId();

    Report report = Report.createPending(customerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "CONC-" + UUID.randomUUID().toString().substring(0, 12));
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    reportId = reportRepository.save(report).getId();
    reviewId = reportReviewRepository.save(new ReportReview(reportId, adjusterId)).getId();
  }

  @AfterEach
  void cleanUp() {
    // @Transactional 롤백이 없으므로 커밋된 데이터를 직접 정리한다(엔티티 간 FK 제약 미매핑이라 순서 무관).
    chatMessageRepository.deleteAll();
    chatRoomRepository.deleteAll();
    reportReviewRepository.deleteAll();
    reportRepository.deleteAll();
    userRepository.deleteAll();
    jdbcTemplate.execute(DROP_UNIQUE_INDEX);
  }

  /** 두 작업을 같은 순간에 출발시키고(래치), 각 스레드가 던진 예외를 모아 돌려준다. */
  private List<Throwable> runConcurrently(Runnable first, Runnable second) throws InterruptedException {
    CountDownLatch startLine = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(2);
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (Runnable task : List.of(first, second)) {
        pool.submit(() -> {
          try {
            startLine.await();
            task.run();
          } catch (Throwable ex) {
            failures.add(ex);
          } finally {
            finished.countDown();
          }
        });
      }
      startLine.countDown();
      assertThat(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }
    return failures;
  }

  @Test
  @DisplayName("같은 제안으로 동시에 개설해도 둘 다 성공하고 같은 방 1개를 돌려받는다")
  void concurrentFirstOpen_isIdempotent() throws InterruptedException {
    List<ConsultationRoomResult> results = Collections.synchronizedList(new ArrayList<>());
    Runnable open = () -> results.add(
        chatRoomCommandService.openConsultationRoom(customerId, adjusterId, reportId, reviewId));

    List<Throwable> failures = runConcurrently(open, open);

    assertThat(failures).isEmpty();
    assertThat(results).hasSize(2);
    assertThat(results.get(0).chatRoomId()).isEqualTo(results.get(1).chatRoomId());
    // 실제로 방을 만든 쪽만 created=true — 진 쪽은 승자의 방을 재사용한다.
    assertThat(results).filteredOn(ConsultationRoomResult::created).hasSize(1);

    assertThat(chatRoomRepository.findByReportId(reportId)).hasSize(1);
    // SYSTEM 안내도 승자 쪽에서 한 번만 남는다(중복 안내 없음).
    assertThat(chatMessageRepository.findByCursor(results.get(0).chatRoomId(), null, null, 10))
        .extracting(message -> message.getMessageType())
        .containsExactly(ChatMessageType.SYSTEM);
  }
}
