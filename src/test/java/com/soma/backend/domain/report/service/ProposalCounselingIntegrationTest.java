package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomListRow;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.chat.service.ChatConsultationCommandService;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
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
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 제안 "상담 수락"(PATCH /reports/{reportId}/proposals/{proposalId}, status=ACCEPTED) 실제 test_db 통합
 * 테스트(design.md §8-6 #11~#13). Mockito 단위 테스트로는 확인할 수 없는 실제 JPA 영속화·크로스-도메인
 * 커밋(제안·리포트 전이 + ChatRoom·SYSTEM 메시지 INSERT가 한 트랜잭션)을 검증한다.
 * {@code @Transactional}이라 테스트 종료 시 롤백되며, 부수효과(Redis 브로드캐스트·AFTER_COMMIT 알림)는
 * 커밋되지 않으므로 발화하지 않는다.
 *
 * <p>요청값 ACCEPTED는 "상담 수락"(채팅방 개설)을 뜻하므로 제안은 실제로 COUNSELING이 된다 — 요청
 * 문자열과 응답 review_status가 다른 건 의도된 계약이다. 최종 채택은 PATCH /chats/{id}/accept 전용이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("제안 상담 시작 → 채팅방 개설 통합")
class ProposalCounselingIntegrationTest {

  @Autowired
  private ReportCommandService reportCommandService;
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

  private User customer;
  private User adjuster1;
  private User adjuster2;

  @BeforeEach
  void setUp() {
    customer = userRepository.save(
        User.create("고객", LocalDate.of(1990, 1, 1), "F", null, null, Role.USER, null));
    adjuster1 = userRepository.save(
        User.create("사정사1", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    adjuster2 = userRepository.save(
        User.create("사정사2", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
  }

  /** 검수가 끝나 제안을 고를 수 있는(AWAITING_ADOPTION) 리포트. */
  private Report awaitingAdoptionReport(String caseNo) {
    Report report = Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", caseNo);
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    return reportRepository.save(report);
  }

  private List<ChatMessage> messagesOf(UUID roomId) {
    return chatMessageRepository.findByCursor(roomId, null, null, 10);
  }

  @Test
  @DisplayName("상담 시작하면 제안·리포트가 COUNSELING이 되고 제안에 연결된 방과 SYSTEM 안내 1건이 저장된다")
  void counseling_persistsRoomAndTransitionsInOneCommit() {
    // Given
    Report report = awaitingAdoptionReport("20260815-001");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When
    ProposalDecisionResponse response = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");

    // Then — 응답 계약
    assertThat(response.chatRoomId()).isNotNull();
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(response.adjusterId()).isEqualTo(adjuster1.getId());

    // Then — 실제 영속 상태
    ChatRoom room = chatRoomRepository.findByReportReviewId(review.getId()).orElseThrow();
    assertThat(room.getId()).isEqualTo(response.chatRoomId());
    assertThat(room.getUserId()).isEqualTo(customer.getId());
    assertThat(room.getAdjusterId()).isEqualTo(adjuster1.getId());
    assertThat(room.getReportId()).isEqualTo(report.getId());
    assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(room.canDecideConsultation()).isTrue();

    assertThat(reportReviewRepository.findById(review.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.COUNSELING);
    assertThat(reportRepository.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.COUNSELING);

    List<ChatMessage> messages = messagesOf(room.getId());
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
    assertThat(messages.get(0).getSenderId()).isNull();
    assertThat(room.getLastMessage()).isEqualTo(messages.get(0).getContent());
  }

  @Test
  @DisplayName("같은 요청을 2회 연속 보내도 방은 1개·SYSTEM 메시지도 1건이다(멱등)")
  void counseling_isIdempotentOnRepeatedRequests() {
    // Given
    Report report = awaitingAdoptionReport("20260815-002");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When
    ProposalDecisionResponse first = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");
    ProposalDecisionResponse second = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");

    // Then
    assertThat(second.chatRoomId()).isEqualTo(first.chatRoomId());
    assertThat(second.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(second.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(chatRoomRepository.findByReportId(report.getId())).hasSize(1);
    assertThat(messagesOf(first.chatRoomId())).hasSize(1);
  }

  @Test
  @DisplayName("같은 리포트의 서로 다른 제안 2건으로 각각 상담을 시작하면 방이 2개 열린다(형제 방)")
  void counseling_opensSeparateRoomPerProposal() {
    // Given
    Report report = awaitingAdoptionReport("20260815-003");
    ReportReview review1 = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ReportReview review2 = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));

    // When
    ProposalDecisionResponse first = reportCommandService.decide(
        customer.getId(), report.getId(), review1.getId(), "ACCEPTED");
    ProposalDecisionResponse second = reportCommandService.decide(
        customer.getId(), report.getId(), review2.getId(), "ACCEPTED");

    // Then
    assertThat(second.chatRoomId()).isNotEqualTo(first.chatRoomId());
    assertThat(chatRoomRepository.findByReportId(report.getId())).hasSize(2);
  }

  @Test
  @DisplayName("상담 시작 후 GET /chats 목록에 방이 보이고 review_status=COUNSELING·안읽음 0이다")
  void counseling_roomAppearsInBothParticipantsRoomList() {
    // Given
    Report report = awaitingAdoptionReport("20260815-004");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When
    ProposalDecisionResponse response = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");

    // Then
    List<ChatRoomListRow> customerRooms = chatRoomRepository.findMyRoomRows(customer.getId());
    assertThat(customerRooms).hasSize(1);
    ChatRoomListRow row = customerRooms.get(0);
    assertThat(row.chatRoomId()).isEqualTo(response.chatRoomId());
    assertThat(row.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(row.status()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(row.lastMessage()).isNotBlank();
    // SYSTEM 메시지는 sender_id가 null이라 안읽음 계산에서 제외된다
    assertThat(row.unreadCount()).isZero();

    assertThat(chatRoomRepository.findMyRoomRows(adjuster1.getId()))
        .extracting(ChatRoomListRow::chatRoomId)
        .containsExactly(response.chatRoomId());
  }

  @Test
  @DisplayName("상담을 시작하기 전에는 그 제안에 연결된 방이 없다")
  void noRoomExistsBeforeCounselingStarts() {
    Report report = awaitingAdoptionReport("20260815-005");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    assertThat(chatRoomRepository.findByReportReviewId(review.getId())).isEmpty();
    assertThat(chatRoomRepository.findMyRoomRows(customer.getId())).isEmpty();
  }

  @Test
  @DisplayName("상담 시작 → PATCH /chats/{id}/accept 연결: 제안 ACCEPTED·리포트 CLOSED·내 방 ACTIVE 유지")
  void counseling_thenChatAcceptCompletesFlow() {
    // Given
    Report report = awaitingAdoptionReport("20260815-006");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ProposalDecisionResponse counseling = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");

    // When
    chatConsultationCommandService.accept(customer.getId(), counseling.chatRoomId());

    // Then
    assertThat(reportReviewRepository.findById(review.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.ACCEPTED);
    Report closed = reportRepository.findById(report.getId()).orElseThrow();
    assertThat(closed.getStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(closed.getAdjusterId()).isEqualTo(adjuster1.getId());
    assertThat(chatRoomRepository.findById(counseling.chatRoomId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.ACTIVE);
    // 개설 안내 + 상담 시작 안내
    assertThat(messagesOf(counseling.chatRoomId())).hasSize(2);
  }

  @Test
  @DisplayName("상담 시작 → PATCH /chats/{id}/reject 후 같은 제안으로 재요청하면 409 INVALID_STATE_TRANSITION")
  void counseling_cannotRestartAfterChatReject() {
    // Given
    Report report = awaitingAdoptionReport("20260815-007");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ProposalDecisionResponse counseling = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");
    chatConsultationCommandService.reject(customer.getId(), counseling.chatRoomId());

    // When & Then
    assertThatThrownBy(() -> reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    assertThat(chatRoomRepository.findByReportId(report.getId())).hasSize(1);
  }

  @Test
  @DisplayName("검수 전(AWAITING_INSPECTION) 리포트로 상담을 시작하면 409이고 방이 만들어지지 않는다")
  void counseling_rejectedForAwaitingInspectionReport_leavesNoOrphanRoom() {
    // Given
    Report report = reportRepository.save(Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260815-008"));
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When & Then
    assertThatThrownBy(() -> reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    assertThat(chatRoomRepository.findByReportReviewId(review.getId())).isEmpty();
  }

  @Test
  @DisplayName("리포트 소유자가 아니면 403 FORBIDDEN이고 방이 만들어지지 않는다")
  void counseling_forbiddenForNonOwner() {
    // Given
    Report report = awaitingAdoptionReport("20260815-009");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When & Then
    assertThatThrownBy(() -> reportCommandService.decide(
        adjuster1.getId(), report.getId(), review.getId(), "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    assertThat(chatRoomRepository.findByReportReviewId(review.getId())).isEmpty();
  }

  @Test
  @DisplayName("status=REJECTED는 no-op이다 — 제안·리포트 DB row가 그대로고 방도 생기지 않는다")
  void rejected_isNoOp_andPersistsNothing() {
    // Given
    Report report = awaitingAdoptionReport("20260815-010");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));

    // When
    ProposalDecisionResponse response = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "REJECTED");

    // Then — 응답 계약
    assertThat(response.chatRoomId()).isNull();
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

    // Then — 실제 영속 상태가 하나도 바뀌지 않았다
    assertThat(reportReviewRepository.findById(review.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.SENT);
    assertThat(reportRepository.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(chatRoomRepository.findByReportReviewId(review.getId())).isEmpty();
    assertThat(chatRoomRepository.findByReportId(report.getId())).isEmpty();
  }

  @Test
  @DisplayName("상담 중(COUNSELING)인 제안에 REJECTED를 보내도 409가 아니라 no-op으로 통과한다")
  void rejected_isNoOp_evenWhenProposalCounseling() {
    // Given — 먼저 상담을 시작해 방까지 열어 둔다
    Report report = awaitingAdoptionReport("20260815-011");
    ReportReview review = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ProposalDecisionResponse counseling = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "ACCEPTED");

    // When
    ProposalDecisionResponse response = reportCommandService.decide(
        customer.getId(), report.getId(), review.getId(), "REJECTED");

    // Then — 상담 상태도 방도 그대로다(정리는 PATCH /chats/{id}/reject 담당)
    assertThat(response.chatRoomId()).isNull();
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(reportReviewRepository.findById(review.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.COUNSELING);
    assertThat(chatRoomRepository.findById(counseling.chatRoomId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.ACTIVE);
  }
}
