package com.soma.backend.domain.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.dto.ConsultationDecisionResponse;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.ChatEventPublisher;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * 상담 수락/거절(설계서 §4 ④·⑤, 확정 결정 §0). chat 도메인이 소유하는 크로스-도메인 쓰기다 —
 * report_reviews·reports 엔티티 메서드를 직접 호출하되 불변식은 각 엔티티가 방어한다. 주체는 방 소유자(user).
 */
@Service
@RequiredArgsConstructor
public class ChatConsultationCommandService {

  private static final String ACCEPT_SYSTEM_MESSAGE = "상담이 시작되었습니다.";
  private static final String REJECT_SYSTEM_MESSAGE = "상담이 종료되었습니다.";
  private static final String SIBLING_CLOSED_SYSTEM_MESSAGE = "다른 사정사가 선택되어 상담이 종료되었습니다.";

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ChatEventPublisher chatEventPublisher;

  /**
   * 상담 수락: 내 제안 ACCEPTED + 형제 제안 REJECTED + report COUNSELING→CLOSED. 내 방은 CLOSED하지 않고
   * ACTIVE로 유지해 수락 후에도 담당 사정사와 대화를 이어가며, 형제 방(다른 사정사)만 CLOSED한다.
   * SYSTEM 메시지 브로드캐스트.
   */
  @Transactional
  public ConsultationDecisionResponse accept(UUID me, UUID roomId) {
    ChatRoom room = loadOwnedConsultableRoom(me, roomId);
    ReportReview myReview = loadReview(room.getReportReviewId());
    Report report = loadReport(room.getReportId());

    myReview.accept();
    rejectSiblingReviews(report.getId(), myReview.getId());
    report.accept(room.getAdjusterId());
    closeSiblingRooms(report.getId(), room.getId());
    appendSystemMessage(room, ACCEPT_SYSTEM_MESSAGE);

    return new ConsultationDecisionResponse(
        room.getId(), room.getStatus(), myReview.getStatus(), report.getId(), report.getStatus());
  }

  /**
   * 상담 거절: 내 제안 REJECTED + 방 CLOSED. 다른 제안은 유지한다. 리포트를 채택 대기로 되돌리는 것은
   * 이 거절로 리포트의 마지막 상담이 끝났을 때만이다 — 형제 제안이 아직 COUNSELING이면 그대로 둔다.
   * SYSTEM 메시지 브로드캐스트.
   *
   * <p>"마지막 상담인가" 판정은 리포트 행을 비관적 잠금으로 잡은 뒤에 한다({@code loadReportForUpdate}) —
   * 같은 리포트의 형제 방을 동시에 거절할 때 두 트랜잭션이 서로를 COUNSELING으로 관찰해 둘 다 복귀를
   * 건너뛰는 레이스를 막기 위함이다.
   */
  @Transactional
  public ConsultationDecisionResponse reject(UUID me, UUID roomId) {
    ChatRoom room = loadOwnedConsultableRoom(me, roomId);
    ReportReview myReview = loadReview(room.getReportReviewId());
    Report report = loadReportForUpdate(room.getReportId());

    myReview.reject();
    if (!hasOtherCounselingReview(report.getId(), myReview.getId())
        && report.getStatus() == ReportStatus.COUNSELING) {
      report.reopenForAdoption();
    }
    room.close();
    appendSystemMessage(room, REJECT_SYSTEM_MESSAGE);

    return new ConsultationDecisionResponse(
        room.getId(), room.getStatus(), myReview.getStatus(), report.getId(), report.getStatus());
  }

  /**
   * 같은 리포트의 다른 제안이 아직 COUNSELING 중인지 — 있으면 리포트를 채택 대기로 되돌리지 않는다
   * (다른 상담이 살아있는데 리포트가 채택 대기 풀로 새어 나가 스케줄러에 NOT_SELECTED로 잘못 전이되는
   * 것을 막는다).
   *
   * <p>호출자가 리포트 행을 잠근 뒤에 실행되므로, 같은 리포트의 거절끼리는 직렬화돼 형제의 최신 상태를
   * 본다. 잠금 없이 이 판정을 하면 동시 거절이 서로를 COUNSELING으로 관찰해 둘 다 복귀를 건너뛴다.
   */
  private boolean hasOtherCounselingReview(UUID reportId, UUID myReviewId) {
    return reportReviewRepository.findByReportId(reportId).stream()
        .anyMatch(sibling -> !sibling.getId().equals(myReviewId)
            && sibling.getStatus() == ReviewStatus.COUNSELING);
  }

  /** 소유자(user)이며 상담 결정이 가능한(파이프라인) 방인지 확인 후 반환한다. */
  private ChatRoom loadOwnedConsultableRoom(UUID me, UUID roomId) {
    ChatRoom room = chatRoomRepository.findById(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    if (!room.isOwnedBy(me)) {
      throw new BusinessException(ErrorCode.CHAT_NOT_ROOM_OWNER);
    }
    if (!room.canDecideConsultation()) {
      throw new BusinessException(ErrorCode.CHAT_CONSULTATION_UNAVAILABLE);
    }
    return room;
  }

  private ReportReview loadReview(UUID reportReviewId) {
    return reportReviewRepository.findById(reportReviewId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
  }

  private Report loadReport(UUID reportId) {
    return reportRepository.findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
  }

  /**
   * 거절 전용 리포트 로드 — 행을 {@code PESSIMISTIC_WRITE}로 잠가 같은 리포트의 동시 거절을 직렬화한다.
   * 잠금 대상은 리포트 1행뿐이고 거절 경로에서 가장 먼저 잡으므로 잠금 순서가 어긋날 여지가 없다.
   */
  private Report loadReportForUpdate(UUID reportId) {
    return reportRepository.findByIdForUpdate(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
  }

  /** 같은 리포트의 형제 제안(다른 사정사)을 일괄 거절한다. 이미 결정된 제안은 건너뛴다. */
  private void rejectSiblingReviews(UUID reportId, UUID myReviewId) {
    List<ReportReview> siblings = reportReviewRepository.findByReportId(reportId);
    for (ReportReview sibling : siblings) {
      if (sibling.getId().equals(myReviewId)) {
        continue;
      }
      if (sibling.isDecidable()) {
        sibling.reject();
      }
    }
  }

  /** 같은 리포트의 형제 방(다른 사정사)을 종료하고 각 방에 종료 안내를 브로드캐스트한다. close()는 멱등. */
  private void closeSiblingRooms(UUID reportId, UUID myRoomId) {
    List<ChatRoom> siblings = chatRoomRepository.findByReportId(reportId);
    for (ChatRoom sibling : siblings) {
      if (sibling.getId().equals(myRoomId) || sibling.getStatus() == ChatRoomStatus.CLOSED) {
        continue;
      }
      sibling.close();
      appendSystemMessage(sibling, SIBLING_CLOSED_SYSTEM_MESSAGE);
    }
  }

  /** SYSTEM 안내 메시지를 저장·미리보기 갱신하고 커밋 후 브로드캐스트한다. */
  private void appendSystemMessage(ChatRoom room, String text) {
    ChatMessage system = chatMessageRepository.save(ChatMessage.system(room.getId(), text));
    room.touchLastMessage(text, system.getCreatedAt());
    chatEventPublisher.publishAfterCommit(new ChatBroadcastMessage(
        room.getId(), system.getId(), null, ChatMessageType.SYSTEM.name(), text, List.of(), system.getCreatedAt()));
  }
}
