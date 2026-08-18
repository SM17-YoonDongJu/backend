package com.soma.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.service.ChatRoomCommandService;
import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.entity.event.ConsultationRequestedEvent;
import com.soma.backend.domain.report.repository.ReportAttachmentRepository;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.sqs.OcrJob;
import com.soma.backend.infra.sqs.OcrJobOutboxPort;

/**
 * ReportCommandService 유스케이스 검증 — 생성(아웃박스 발행 포함) + 제안 상담 수락.
 *
 * <p>PATCH /reports/{reportId}/proposals/{proposalId}는 "상담 수락 전용"이다 — status=ACCEPTED는
 * 채팅방 개설(제안 SENT→COUNSELING)을 뜻하고, status=REJECTED는 아무 동작도 하지 않는다. 최종 채택
 * (제안 ACCEPTED·리포트 CLOSED)과 거절은 PATCH /chats/{chatRoomId}/accept·reject 전용이라
 * 이 서비스에서는 검증하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ReportCommandServiceTest {

  @Mock
  private UserClaimRepository userClaimRepository;
  @Mock
  private ReportRepository reportRepository;
  @Mock
  private ReportAttachmentRepository reportAttachmentRepository;
  @Mock
  private ReportReviewRepository reportReviewRepository;
  @Mock
  private OcrJobOutboxPort ocrJobOutboxPort;
  @Mock
  private ChatRoomCommandService chatRoomCommandService;
  @Mock
  private ApplicationEventPublisher eventPublisher;
  @InjectMocks
  private ReportCommandService service;

  private final UUID userId = UUID.randomUUID();
  private final UUID reportId = UUID.randomUUID();
  private final UUID proposalId = UUID.randomUUID();
  private final UUID adjusterId = UUID.randomUUID();

  private static <T> T withId(T entity) {
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    return entity;
  }

  @Test
  void createReport_persistsClaimReportAttachments_andEnqueuesOcrJobPerDocument() {
    given(reportRepository.nextCaseNoSequence(any())).willReturn(1);
    given(userClaimRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportAttachmentRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));

    CreateReportRequest.Document pdf =
        new CreateReportRequest.Document("https://bucket.s3.ap-northeast-2.amazonaws.com/uploads/a.pdf",
            "진단서.pdf", "진단서", ".pdf");
    CreateReportRequest.Document img =
        new CreateReportRequest.Document("https://bucket.s3.ap-northeast-2.amazonaws.com/uploads/b.jpg",
            "사진.jpg", "기타", ".jpg");
    CreateReportRequest request = new CreateReportRequest(
        UUID.randomUUID(), AccidentType.MEDICAL_INDEMNITY, LocalDate.now(), List.of("급성 충수염"),
        1_420_000, List.of(), "사고 경위", null, List.of(pdf, img), "질문");

    CreateReportResponse response = service.createReport(userId, request);

    assertThat(response.status()).isEqualTo("AWAITING_INSPECTION");
    assertThat(response.reportId()).isNotNull();
    verify(reportAttachmentRepository, times(2)).save(any());

    ArgumentCaptor<OcrJob> jobCaptor = ArgumentCaptor.forClass(OcrJob.class);
    verify(ocrJobOutboxPort, times(2)).enqueue(jobCaptor.capture());
    OcrJob published = jobCaptor.getValue();
    assertThat(published.claimId()).isNotNull();
    assertThat(published.reportId()).isNotNull();
    assertThat(published.attachmentId()).isNotNull();

    List<OcrJob> jobs = jobCaptor.getAllValues();
    assertThat(jobs).extracting(OcrJob::docIndex).containsExactly(1, 2);
    assertThat(jobs).extracting(OcrJob::docTotal).containsExactly(2, 2);
  }

  @Test
  void createReport_succeeds_whenProductIdNull() {
    given(reportRepository.nextCaseNoSequence(any())).willReturn(1);
    given(userClaimRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));
    given(reportRepository.save(any())).willAnswer(inv -> withId(inv.getArgument(0)));

    CreateReportRequest request = new CreateReportRequest(
        null, AccidentType.MEDICAL_INDEMNITY, LocalDate.now(), List.of("급성 충수염"), null, List.of(),
        null, null, List.of(), null);

    CreateReportResponse response = service.createReport(userId, request);

    assertThat(response.status()).isEqualTo("AWAITING_INSPECTION");
    assertThat(response.reportId()).isNotNull();
    verify(ocrJobOutboxPort, never()).enqueue(any());
  }

  @Test
  void createReport_throwsMissingRequiredField_whenAccidentTypeNull() {
    CreateReportRequest request = new CreateReportRequest(
        UUID.randomUUID(), null, LocalDate.now(), List.of(), null, List.of(),
        null, null, List.of(), null);

    assertThatThrownBy(() -> service.createReport(userId, request))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    verify(ocrJobOutboxPort, never()).enqueue(any());
  }

  // ---------------------------------------------------------------------------------------------
  // status=ACCEPTED — 상담 수락(채팅방 개설). 응답 review_status는 실제 도메인 값인 COUNSELING이다.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("ACCEPTED: 제안·리포트를 COUNSELING으로 전이하고 개설된 방 id를 응답에 담는다")
  void decide_accepted_opensRoomAndTransitions() {
    // Given
    UUID chatRoomId = UUID.randomUUID();
    Report report = reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION);
    ReportReview review = review();
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review));
    given(chatRoomCommandService.openConsultationRoom(userId, adjusterId, reportId, proposalId))
        .willReturn(new ConsultationRoomResult(chatRoomId, true));

    // When
    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "ACCEPTED");

    // Then — 요청값 "ACCEPTED"(상담 수락)와 응답값 COUNSELING(실제 제안 상태)이 다른 건 의도된 계약이다
    assertThat(response.chatRoomId()).isEqualTo(chatRoomId);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(report.getStatus()).isEqualTo(ReportStatus.COUNSELING);
    verify(chatRoomCommandService, times(1))
        .openConsultationRoom(userId, adjusterId, reportId, proposalId);
  }

  @Test
  @DisplayName("ACCEPTED: 미채택(NOT_SELECTED) 리포트에서도 상담을 재개할 수 있다")
  void decide_accepted_resumesFromNotSelectedReport() {
    UUID chatRoomId = UUID.randomUUID();
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.NOT_SELECTED)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));
    given(chatRoomCommandService.openConsultationRoom(any(), any(), any(), any()))
        .willReturn(new ConsultationRoomResult(chatRoomId, true));

    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "ACCEPTED");

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    assertThat(response.chatRoomId()).isEqualTo(chatRoomId);
  }

  @Test
  @DisplayName("ACCEPTED: 방이 새로 열렸으면 사정사 수신 CONSULT_REQUESTED 이벤트를 1회 발행한다")
  void decide_accepted_publishesConsultRequestedEvent_whenRoomCreated() {
    // Given
    UUID chatRoomId = UUID.randomUUID();
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));
    given(chatRoomCommandService.openConsultationRoom(any(), any(), any(), any()))
        .willReturn(new ConsultationRoomResult(chatRoomId, true));

    // When
    service.decide(userId, reportId, proposalId, "ACCEPTED");

    // Then
    ArgumentCaptor<ConsultationRequestedEvent> eventCaptor =
        ArgumentCaptor.forClass(ConsultationRequestedEvent.class);
    verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
    ConsultationRequestedEvent event = eventCaptor.getValue();
    assertThat(event.adjusterId()).isEqualTo(adjusterId);
    assertThat(event.userId()).isEqualTo(userId);
    assertThat(event.reportId()).isEqualTo(reportId);
    assertThat(event.proposalId()).isEqualTo(proposalId);
    assertThat(event.chatRoomId()).isEqualTo(chatRoomId);
  }

  @Test
  @DisplayName("ACCEPTED: 이미 방이 있으면(재요청·더블클릭) 같은 방 id를 돌려주고 알림을 재발행하지 않는다")
  void decide_accepted_doesNotPublish_whenRoomAlreadyExists() {
    // Given — 제안이 이미 COUNSELING이라 startCounseling은 멱등 no-op이다
    UUID chatRoomId = UUID.randomUUID();
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.COUNSELING)));
    ReportReview counseling = review();
    ReflectionTestUtils.setField(counseling, "status", ReviewStatus.COUNSELING);
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(counseling));
    given(chatRoomCommandService.openConsultationRoom(any(), any(), any(), any()))
        .willReturn(new ConsultationRoomResult(chatRoomId, false));

    // When
    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "ACCEPTED");

    // Then
    assertThat(response.chatRoomId()).isEqualTo(chatRoomId);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.COUNSELING);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.COUNSELING);
    verify(eventPublisher, never()).publishEvent(any(ConsultationRequestedEvent.class));
  }

  @Test
  @DisplayName("ACCEPTED: 검수 전(AWAITING_INSPECTION) 리포트면 409, 방 개설은 시도조차 하지 않는다(고아 방 방지)")
  void decide_accepted_invalidStateTransition_whenReportAwaitingInspection() {
    // Given
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.AWAITING_INSPECTION)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    // When & Then — 상태 전이 검증을 방 INSERT 앞에 모아둔 순서 계약(실제 롤백은 트랜잭션이 담당)
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any(ConsultationRequestedEvent.class));
  }

  @Test
  @DisplayName("ACCEPTED: 종결(CLOSED) 리포트면 409, 방 개설을 시도하지 않는다")
  void decide_accepted_invalidStateTransition_whenReportClosed() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.CLOSED)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("ACCEPTED: AI 가드레일 차단(BLOCKED) 리포트면 409, 방 개설을 시도하지 않는다")
  void decide_accepted_invalidStateTransition_whenReportBlocked() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.BLOCKED)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("ACCEPTED: 이미 채택(ACCEPTED)된 제안이면 409, 방 개설을 시도하지 않는다")
  void decide_accepted_invalidStateTransition_whenProposalAlreadyAccepted() {
    Report report = reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    ReportReview decided = review();
    ReflectionTestUtils.setField(decided, "status", ReviewStatus.ACCEPTED);
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(decided));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    // 제안 검증이 리포트 전이보다 먼저다 — 리포트 상태도 오염되지 않는다
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("ACCEPTED: 이미 거절(REJECTED)된 제안이면 409, 방 개설을 시도하지 않는다")
  void decide_accepted_invalidStateTransition_whenProposalAlreadyRejected() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION)));
    ReportReview decided = review();
    ReflectionTestUtils.setField(decided, "status", ReviewStatus.REJECTED);
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(decided));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------------------------
  // status=REJECTED — 완전 no-op. 실제 거절은 PATCH /chats/{chatRoomId}/reject 전용이다.
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest(name = "review={0}")
  @EnumSource(ReviewStatus.class)
  @DisplayName("REJECTED: 제안이 어떤 상태든 상태를 바꾸지 않고 200으로 통과한다(no-op)")
  void decide_rejected_isNoOp_forAnyReviewStatus(ReviewStatus initial) {
    // Given
    Report report = reportOwnedBy(userId, ReportStatus.AWAITING_ADOPTION);
    ReportReview review = review();
    ReflectionTestUtils.setField(review, "status", initial);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review));

    // When
    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "REJECTED");

    // Then — 제안·리포트 어느 쪽도 건드리지 않는다
    assertThat(response.reviewStatus()).isEqualTo(initial);
    assertThat(review.getStatus()).isEqualTo(initial);
    assertThat(response.reportStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(report.getStatus()).isEqualTo(ReportStatus.AWAITING_ADOPTION);
    assertThat(response.chatRoomId()).isNull();
    assertThat(response.adjusterId()).isEqualTo(adjusterId);
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
    verify(eventPublisher, never()).publishEvent(any(ConsultationRequestedEvent.class));
  }

  @ParameterizedTest(name = "report={0}")
  @EnumSource(ReportStatus.class)
  @DisplayName("REJECTED: 리포트가 어떤 상태든(CLOSED·BLOCKED 포함) 상태 검증 없이 통과한다")
  void decide_rejected_isNoOp_forAnyReportStatus(ReportStatus initial) {
    // Given
    Report report = reportOwnedBy(userId, initial);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(review()));

    // When
    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "REJECTED");

    // Then
    assertThat(response.reportStatus()).isEqualTo(initial);
    assertThat(report.getStatus()).isEqualTo(initial);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.SENT);
    assertThat(response.chatRoomId()).isNull();
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("REJECTED: 제안·리포트가 모두 종료 상태여도 409가 아니라 200이다")
  void decide_rejected_isNoOp_whenBothTerminal() {
    Report report = reportOwnedBy(userId, ReportStatus.CLOSED);
    ReportReview accepted = review();
    ReflectionTestUtils.setField(accepted, "status", ReviewStatus.ACCEPTED);
    given(reportRepository.findById(reportId)).willReturn(Optional.of(report));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.of(accepted));

    ProposalDecisionResponse response = service.decide(userId, reportId, proposalId, "REJECTED");

    assertThat(response.reportStatus()).isEqualTo(ReportStatus.CLOSED);
    assertThat(response.reviewStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(response.chatRoomId()).isNull();
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------------------------
  // 공통 — 소유권·존재·요청값 검증
  // ---------------------------------------------------------------------------------------------

  @Test
  void decide_forbidden_whenNotOwner() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(UUID.randomUUID(), ReportStatus.COUNSELING)));

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("REJECTED가 no-op이어도 소유권 검증은 건너뛰지 않는다 — 남의 리포트 상태 조회 통로가 되면 안 된다")
  void decide_rejected_forbidden_whenNotOwner() {
    // Given — REJECTED는 아무 상태도 바꾸지 않지만 응답에 adjuster_id·report_status·review_status를 담는다.
    // 소유권 검증이 ACCEPTED 분기 안으로 밀려나는 리팩터가 생기면 그 순간 타인 리포트 정보 노출이 되므로
    // no-op 경로에서도 403이 나는 것을 별도로 고정한다.
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(UUID.randomUUID(), ReportStatus.AWAITING_ADOPTION)));

    // When & Then
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "REJECTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    verify(reportReviewRepository, never()).findById(any());
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  void decide_proposalNotFound_whenReviewMissing() {
    given(reportRepository.findById(reportId))
        .willReturn(Optional.of(reportOwnedBy(userId, ReportStatus.COUNSELING)));
    given(reportReviewRepository.findById(proposalId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "ACCEPTED"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PROPOSAL_NOT_FOUND));
  }

  @Test
  @DisplayName("status=COUNSELING은 더 이상 유효한 요청값이 아니라 400 VALIDATION_ERROR다")
  void decide_validationError_whenStatusCounseling() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "COUNSELING"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("status=SENT는 enum이지만 결정 값이 아니라 400 VALIDATION_ERROR다")
  void decide_validationError_whenStatusSent() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "SENT"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  void decide_validationError_whenStatusInvalid() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "MAYBE"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }

  @Test
  @DisplayName("소문자 accepted는 허용값이 아니다(대소문자 구분) — 400 VALIDATION_ERROR")
  void decide_validationError_whenStatusLowercase() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "accepted"))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    verify(chatRoomCommandService, never()).openConsultationRoom(any(), any(), any(), any());
  }

  @Test
  @DisplayName("status가 비어 있으면 400 MISSING_REQUIRED_FIELD")
  void decide_missingRequiredField_whenStatusBlank() {
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, "  "))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
    assertThatThrownBy(() -> service.decide(userId, reportId, proposalId, null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  private Report reportOwnedBy(UUID ownerId, ReportStatus status) {
    Report report = Report.createPending(
        ownerId, null, null, AccidentType.MEDICAL_INDEMNITY, "질문", "20260709-001");
    ReflectionTestUtils.setField(report, "id", reportId);
    ReflectionTestUtils.setField(report, "status", status);
    return report;
  }

  private ReportReview review() {
    ReportReview review = new ReportReview(reportId, adjusterId);
    ReflectionTestUtils.setField(review, "id", proposalId);
    return review;
  }
}
