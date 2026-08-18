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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.user.entity.Role;
import com.soma.backend.domain.user.entity.User;
import com.soma.backend.domain.user.repository.UserRepository;
import com.soma.backend.infra.redis.ChatEventPublisher;

/**
 * 같은 리포트의 형제 상담방을 동시에 거절하는 레이스의 실제 test_db 통합 테스트(CodeRabbit 지적, PR #249).
 *
 * <p>거절은 "다른 COUNSELING 제안이 없으면 리포트를 채택 대기로 되돌린다"를 판정하는데, 잠금이 없으면 두
 * 트랜잭션이 서로를 아직 COUNSELING으로 관찰해 둘 다 되돌리기를 건너뛴다. 그러면 제안은 전부 REJECTED인데
 * 리포트만 COUNSELING에 갇히고, COUNSELING을 벗어나는 경로가 더는 없어 영구히 멈춘다
 * ({@code ReportNotSelectionSweeper}도 COUNSELING은 스윕 대상이 아니다).
 *
 * <p>{@code @Transactional}을 두지 않는다 — 두 스레드가 서로의 커밋을 관찰해야 하는 테스트라 각 요청이
 * 실제로 커밋돼야 한다. 커밋한 데이터는 {@code @AfterEach}에서 정리한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("상담 거절 동시성 통합 테스트")
class ChatConsultationRejectConcurrencyIntegrationTest {

  private static final int TIMEOUT_SECONDS = 30;

  @Autowired
  private ChatConsultationCommandService chatConsultationCommandService;
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

  /** Redis I/O를 테스트에서 끊는다 — 실제 커밋 경로라 afterCommit 브로드캐스트가 진짜로 발화한다. */
  @MockitoBean
  private ChatEventPublisher chatEventPublisher;

  private UUID customerId;
  private UUID reportId;
  private UUID reviewAId;
  private UUID reviewBId;
  private UUID roomAId;
  private UUID roomBId;

  @BeforeEach
  void setUp() {
    User customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    User adjusterA = userRepository.save(
        User.create("사정사A", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    User adjusterB = userRepository.save(
        User.create("사정사B", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    customerId = customer.getId();

    Report report = Report.createPending(customerId, null, null, AccidentType.MEDICAL_INDEMNITY,
        "질문", "CONC-" + UUID.randomUUID().toString().substring(0, 12));
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    report.applyReviewTransition(ReportStatus.COUNSELING);
    reportId = reportRepository.save(report).getId();

    reviewAId = saveCounselingReview(adjusterA.getId());
    reviewBId = saveCounselingReview(adjusterB.getId());
    roomAId = saveActiveRoom(adjusterA.getId(), reviewAId);
    roomBId = saveActiveRoom(adjusterB.getId(), reviewBId);
  }

  @AfterEach
  void cleanUp() {
    // @Transactional 롤백이 없으므로 커밋된 데이터를 직접 정리한다(엔티티 간 FK 제약 미매핑이라 순서 무관).
    chatMessageRepository.deleteAll();
    chatRoomRepository.deleteAll();
    reportReviewRepository.deleteAll();
    reportRepository.deleteAll();
    userRepository.deleteAll();
  }

  /** 상담 진행 중(COUNSELING) 제안. 트랜잭션 밖이라 상태 전이를 저장 전에 끝내야 반영된다. */
  private UUID saveCounselingReview(UUID adjusterId) {
    ReportReview review = new ReportReview(reportId, adjusterId);
    review.startCounseling();
    return reportReviewRepository.save(review).getId();
  }

  private UUID saveActiveRoom(UUID adjusterId, UUID reportReviewId) {
    ChatRoom room = ChatRoomFixture.build(
        customerId, adjusterId, reportId, reportReviewId, ChatRoomStatus.ACTIVE);
    return chatRoomRepository.save(room).getId();
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

  private ReviewStatus statusOfReview(UUID reviewId) {
    return reportReviewRepository.findById(reviewId).orElseThrow().getStatus();
  }

  private ChatRoomStatus statusOfRoom(UUID roomId) {
    return chatRoomRepository.findById(roomId).orElseThrow().getStatus();
  }

  @Test
  @DisplayName("형제 상담방 2개를 동시에 거절해도 둘 다 성공하고 리포트가 채택 대기로 복귀한다")
  void concurrentSiblingRejects_reopenReportForAdoption() throws InterruptedException {
    List<Throwable> failures = runConcurrently(
        () -> chatConsultationCommandService.reject(customerId, roomAId),
        () -> chatConsultationCommandService.reject(customerId, roomBId));

    assertThat(failures).isEmpty();
    // 리포트 잠금이 두 거절을 직렬화하므로, 나중에 커밋하는 쪽이 형제가 이미 REJECTED임을 보고 되돌린다.
    assertThat(reportRepository.findById(reportId).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(statusOfReview(reviewAId)).isEqualTo(ReviewStatus.REJECTED);
    assertThat(statusOfReview(reviewBId)).isEqualTo(ReviewStatus.REJECTED);
    assertThat(statusOfRoom(roomAId)).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(statusOfRoom(roomBId)).isEqualTo(ChatRoomStatus.CLOSED);
  }
}
