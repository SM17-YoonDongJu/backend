package com.soma.backend.domain.chat.service;

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

import com.soma.backend.domain.chat.ChatRoomFixture;
import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
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
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 상담 수락/거절(설계서 §4 ④·⑤) 실제 test_db 통합 테스트. Mockito 단위 테스트로는 확인할 수 없는
 * 실제 JPA 영속화·조회(findByReportId 등)를 거친 상태 전이를 검증한다. {@code @Transactional}로
 * 테스트 종료 시 롤백된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatConsultationCommandServiceIntegrationTest {

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

  private Report counselingReport() {
    Report report = Report.createPending(
        customer.getId(), null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260716-001");
    report.applyReviewTransition(ReportStatus.AWAITING_ADOPTION);
    report.applyReviewTransition(ReportStatus.COUNSELING);
    return reportRepository.save(report);
  }

  /** 상담이 진행 중인(COUNSELING) 제안 — 채팅 화면에서 수락/거절을 누를 수 있는 실제 상태다. */
  private ReportReview counselingReview(UUID reportId, UUID adjusterId) {
    ReportReview review = reportReviewRepository.save(new ReportReview(reportId, adjusterId));
    review.startCounseling();
    return review;
  }

  private ChatRoom savedRoom(UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    ChatRoom room = ChatRoomFixture.build(userId, adjusterId, reportId, reportReviewId, ChatRoomStatus.ACTIVE);
    return chatRoomRepository.save(room);
  }

  @Test
  @DisplayName("정상 수락: 내 제안 ACCEPTED·형제 제안 REJECTED·report CLOSED·내 방 ACTIVE 유지·형제 방 CLOSED"
      + " + SYSTEM 메시지 저장")
  void accept_persistsAtomicTransitionAcrossAggregates() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ReportReview siblingReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());
    ChatRoom siblingRoom = savedRoom(customer.getId(), adjuster2.getId(), report.getId(), siblingReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.accept(customer.getId(), myRoom.getId());

    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);

    ReportReview reloadedMyReview = reportReviewRepository.findById(myReview.getId()).orElseThrow();
    ReportReview reloadedSibling = reportReviewRepository.findById(siblingReview.getId()).orElseThrow();
    Report reloadedReport = reportRepository.findById(report.getId()).orElseThrow();
    ChatRoom reloadedMyRoom = chatRoomRepository.findById(myRoom.getId()).orElseThrow();
    ChatRoom reloadedSiblingRoom = chatRoomRepository.findById(siblingRoom.getId()).orElseThrow();

    assertThat(reloadedMyReview.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(reloadedSibling.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(reloadedReport.getStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(reloadedReport.getAdjusterId()).isEqualTo(adjuster1.getId());
    assertThat(reloadedMyRoom.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(reloadedSiblingRoom.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);

    List<ChatMessage> myRoomMessages = chatMessageRepository.findByCursor(myRoom.getId(), null, null, 10);
    assertThat(myRoomMessages).hasSize(1);
    assertThat(myRoomMessages.get(0).getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
    assertThat(myRoomMessages.get(0).getSenderId()).isNull();

    List<ChatMessage> siblingRoomMessages =
        chatMessageRepository.findByCursor(siblingRoom.getId(), null, null, 10);
    assertThat(siblingRoomMessages).hasSize(1);
    assertThat(siblingRoomMessages.get(0).getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
  }

  @Test
  @DisplayName("정상 거절: 내 제안 REJECTED·report AWAITING_ADOPTION·방 CLOSED, 다른 제안은 유지")
  void reject_persistsAtomicTransitionAndKeepsOtherReviews() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ReportReview otherReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.reject(customer.getId(), myRoom.getId());

    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);

    ReportReview reloadedMyReview = reportReviewRepository.findById(myReview.getId()).orElseThrow();
    ReportReview reloadedOther = reportReviewRepository.findById(otherReview.getId()).orElseThrow();
    Report reloadedReport = reportRepository.findById(report.getId()).orElseThrow();
    ChatRoom reloadedRoom = chatRoomRepository.findById(myRoom.getId()).orElseThrow();

    assertThat(reloadedMyReview.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(reloadedOther.getStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(reloadedReport.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(reloadedRoom.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
  }

  /**
   * 같은 리포트로 상담을 2건 동시에 진행하다 하나를 거절하는 상황. 살아있는 상담이 있는데 리포트를 채택
   * 대기로 되돌리면 {@code ReportNotSelectionSweeper}가 접수 7일 뒤 그 리포트를 NOT_SELECTED로 잘못
   * 전이시킬 수 있다 — 리포트는 COUNSELING을 유지해야 한다.
   */
  @Test
  @DisplayName("형제 제안이 아직 COUNSELING이면 리포트는 COUNSELING을 유지하고 내 제안·내 방만 정리된다")
  void reject_keepsReportCounseling_whenSiblingStillCounseling() {
    Report report = counselingReport();
    ReportReview myReview = counselingReview(report.getId(), adjuster1.getId());
    ReportReview siblingReview = counselingReview(report.getId(), adjuster2.getId());
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());
    ChatRoom siblingRoom =
        savedRoom(customer.getId(), adjuster2.getId(), report.getId(), siblingReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.reject(customer.getId(), myRoom.getId());

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.CLOSED);

    assertThat(reportRepository.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.COUNSELING);
    assertThat(reportReviewRepository.findById(myReview.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.REJECTED);
    assertThat(reportReviewRepository.findById(siblingReview.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.COUNSELING);
    assertThat(chatRoomRepository.findById(myRoom.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(chatRoomRepository.findById(siblingRoom.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(chatMessageRepository.findByCursor(siblingRoom.getId(), null, null, 10)).isEmpty();
  }

  /** 형제 판정 기준은 "형제가 있는가"가 아니라 "형제가 COUNSELING인가"임을 실 DB 조회로 고정한다. */
  @Test
  @DisplayName("형제 제안이 SENT·ACCEPTED·REJECTED뿐이면 리포트는 정상적으로 채택 대기로 돌아온다")
  void reject_reopensReport_whenNoSiblingIsCounseling() {
    User adjuster3 = userRepository.save(
        User.create("사정사3", LocalDate.of(1985, 1, 1), "M", null, null, Role.CERTIFICATED_ADJUSTER, null));
    Report report = counselingReport();
    ReportReview myReview = counselingReview(report.getId(), adjuster1.getId());
    ReportReview sentSibling = reportReviewRepository.save(new ReportReview(report.getId(), adjuster2.getId()));
    ReportReview rejectedSibling = reportReviewRepository.save(new ReportReview(report.getId(), adjuster3.getId()));
    rejectedSibling.reject();
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.reject(customer.getId(), myRoom.getId());

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(reportRepository.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(reportReviewRepository.findById(sentSibling.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.SENT);
    assertThat(chatRoomRepository.findById(myRoom.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.CLOSED);
  }

  /**
   * {@code reopenForAdoption()}은 COUNSELING이 아닌 리포트에서 409를 던지므로, 가드가 없으면 거절 자체가
   * 롤백된다. 리포트 경로 채택({@code PATCH /reports/.../proposals/...} status=ACCEPTED)이 형제 제안·형제 방을
   * 캐스케이드로 정리하지 않아(design.md §9 #1) "리포트는 CLOSED인데 내 제안은 아직 COUNSELING"이 실제로 남는다.
   */
  @Test
  @DisplayName("리포트가 이미 CLOSED여도 제안 거절·방 종료는 성립하고 리포트 전이만 건너뛴다(409 없음)")
  void reject_skipsReportTransition_whenReportAlreadyClosed() {
    Report report = counselingReport();
    ReportReview myReview = counselingReview(report.getId(), adjuster1.getId());
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());
    report.accept(adjuster2.getId());

    ConsultationDecisionResponse response = chatConsultationCommandService.reject(customer.getId(), myRoom.getId());

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
    assertThat(response.chatRoomStatus()).isEqualTo(ChatRoomStatus.CLOSED);

    assertThat(reportRepository.findById(report.getId()).orElseThrow().getStatus())
        .isEqualTo(ReportStatus.CLOSED);
    assertThat(reportReviewRepository.findById(myReview.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.REJECTED);
    assertThat(chatRoomRepository.findById(myRoom.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.CLOSED);
    List<ChatMessage> messages = chatMessageRepository.findByCursor(myRoom.getId(), null, null, 10);
    assertThat(messages).hasSize(1);
    assertThat(messages.get(0).getMessageType()).isEqualTo(ChatMessageType.SYSTEM);
  }

  /**
   * 형제 COUNSELING 가드가 열어준 시나리오를 끝까지 이어본다 — 상담 2건이 동시에 진행되다 A를 거절하고
   * (리포트는 COUNSELING 유지) 이어서 B를 수락하면 리포트가 담당 사정사 B로 정상 종결돼야 한다.
   * 거절 단계에서 리포트가 채택 대기로 새어 나가면 남은 상담이 스케줄러(NOT_SELECTED)와 경합한다.
   */
  @Test
  @DisplayName("상담 2건 중 하나를 거절한 뒤 남은 상담을 수락하면 리포트가 CLOSED로 정상 종결된다")
  void rejectThenAcceptSibling_closesReportWithRemainingAdjuster() {
    Report report = counselingReport();
    ReportReview reviewA = counselingReview(report.getId(), adjuster1.getId());
    ReportReview reviewB = counselingReview(report.getId(), adjuster2.getId());
    ChatRoom roomA = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), reviewA.getId());
    ChatRoom roomB = savedRoom(customer.getId(), adjuster2.getId(), report.getId(), reviewB.getId());

    chatConsultationCommandService.reject(customer.getId(), roomA.getId());
    ConsultationDecisionResponse accepted =
        chatConsultationCommandService.accept(customer.getId(), roomB.getId());

    assertThat(accepted.reportStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(accepted.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(accepted.chatRoomStatus()).isEqualTo(ChatRoomStatus.ACTIVE);

    Report reloadedReport = reportRepository.findById(report.getId()).orElseThrow();
    assertThat(reloadedReport.getStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(reloadedReport.getAdjusterId()).isEqualTo(adjuster2.getId());
    assertThat(reportReviewRepository.findById(reviewA.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.REJECTED);
    assertThat(reportReviewRepository.findById(reviewB.getId()).orElseThrow().getStatus())
        .isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(chatRoomRepository.findById(roomA.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.CLOSED);
    assertThat(chatRoomRepository.findById(roomB.getId()).orElseThrow().getStatus())
        .isEqualTo(ChatRoomStatus.ACTIVE);
    // roomA는 자신의 reject()에서 이미 종료 안내를 남겼다 — roomB의 accept()가 형제 방을 정리할 때
    // 이미 CLOSED인 roomA를 건너뛰므로 종료 안내가 중복되지 않는다.
    assertThat(chatMessageRepository.findByCursor(roomA.getId(), null, null, 10))
        .extracting(ChatMessage::getContent)
        .containsExactly("상담이 종료되었습니다.");
  }

  @Test
  @DisplayName("소유자가 아닌 사용자가 수락 시도하면 CHAT_NOT_ROOM_OWNER(403)")
  void accept_notOwner_throwsForbidden() {
    Report report = counselingReport();
    ReportReview myReview = reportReviewRepository.save(new ReportReview(report.getId(), adjuster1.getId()));
    ChatRoom myRoom = savedRoom(customer.getId(), adjuster1.getId(), report.getId(), myReview.getId());

    assertThatThrownBy(() -> chatConsultationCommandService.accept(UUID.randomUUID(), myRoom.getId()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_NOT_ROOM_OWNER));
  }

  @Test
  @DisplayName("검색 방(report_review_id null)에서 수락 시도하면 CHAT_CONSULTATION_UNAVAILABLE(409)")
  void accept_searchRoom_throwsConsultationUnavailable() {
    ChatRoom searchRoom = savedRoom(customer.getId(), adjuster1.getId(), null, null);

    assertThatThrownBy(() -> chatConsultationCommandService.accept(customer.getId(), searchRoom.getId()))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE));
  }
}
