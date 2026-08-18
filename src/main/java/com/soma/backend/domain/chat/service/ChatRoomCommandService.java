package com.soma.backend.domain.chat.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soma.backend.domain.chat.dto.ConsultationRoomResult;
import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.entity.ChatMessageType;
import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.infra.redis.ChatEventPublisher;
import com.soma.backend.infra.redis.dto.ChatBroadcastMessage;

/**
 * 상담 채팅방 개설 커맨드 — 고객이 제안의 "상담 수락"을 누르는 시점. ChatRoom Aggregate는 chat 도메인이
 * 소유하므로 그 홈도 여기다 — report 도메인은 이 서비스에 위임할 뿐 ChatRoom 엔티티·리포지토리를 직접
 * 만지지 않는다.
 *
 * <p>이 클래스는 report 패키지를 전혀 import하지 않는다(식별자를 UUID 파라미터로만 받는다). 반대 방향
 * 크로스-도메인 쓰기를 하는 {@link ChatConsultationCommandService}(report_reviews·reports를 직접 조작)와
 * 섞지 않아, 클래스 단위 의존 그래프가 순환하지 않게 유지하기 위함이다.
 *
 * <p>트랜잭션은 호출자(report의 커맨드 유스케이스)의 것을 그대로 쓴다(기본 전파 REQUIRED) —
 * 제안·리포트 상태 전이와 안내 메시지가 한 커밋으로 묶여야 "상태는 COUNSELING인데 안내가 없다"가
 * 생기지 않는다. 예외는 방 INSERT 한 문장뿐이다({@link ChatRoomInsertOperator}, REQUIRES_NEW) —
 * 동시 최초 개설의 UNIQUE 충돌을 호출자 트랜잭션에서 격리하기 위해서다. 그 대가로 바깥 트랜잭션이
 * 뒤이어 실패하면 방 행만 남을 수 있는데, 호출자가 던질 수 있는 검증을 모두 이 위임 앞에 끝내 두었고
 * (ReportCommandService#startCounseling) 남은 방은 제안당 1개로 고정돼 재요청 시 그대로 재사용된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomCommandService {

  private static final String CONSULT_OPENED_SYSTEM_MESSAGE =
      "상담 채팅방이 열렸습니다. 손해사정사와 상담을 시작해보세요.";

  private final ChatRoomRepository chatRoomRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatRoomInsertOperator chatRoomInsertOperator;
  private final ChatEventPublisher chatEventPublisher;

  /**
   * 제안에 연결된 상담 방을 멱등하게 확보한다. 이미 있으면 그대로 반환하고(더블클릭·재요청), 없으면
   * 개설 후 SYSTEM 안내 메시지를 남긴다.
   *
   * <p>동시 최초 개설(선조회를 둘 다 통과)도 멱등이다 — INSERT는 별도 트랜잭션({@link
   * ChatRoomInsertOperator})에 격리돼 있어 UNIQUE 인덱스(V44)에 걸린 쪽만 롤백되고, 진 쪽은 다시 조회해
   * 승자의 방을 그대로 돌려준다. 격리하지 않으면 진 쪽 요청이 409 DUPLICATE_RESOURCE로 실패해
   * "이미 있으면 그대로 반환"이라는 계약이 깨진다.
   *
   * <p>SYSTEM 안내 메시지는 실제로 방을 만든 쪽에서만, 그리고 격리된 INSERT 트랜잭션이 아니라 이
   * 트랜잭션에서 남긴다 — 바깥 트랜잭션(제안·리포트 상태 전이)이 실패하면 안내 메시지도 함께 롤백돼야
   * 하기 때문이다.
   */
  @Transactional
  public ConsultationRoomResult openConsultationRoom(
      UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    Optional<ChatRoom> existing = chatRoomRepository.findByReportReviewId(reportReviewId);
    if (existing.isPresent()) {
      return new ConsultationRoomResult(existing.get().getId(), false);
    }

    boolean created;
    try {
      created = chatRoomInsertOperator.insertConsultationRoom(userId, adjusterId, reportId, reportReviewId);
    } catch (DataIntegrityViolationException ex) {
      // 동시 최초 개설 경쟁에서 졌다 — 승자가 이미 커밋한 방을 아래에서 다시 조회해 그대로 쓴다.
      created = false;
      log.debug("동시 최초 상담방 개설 감지 — reportReviewId={}", reportReviewId);
    }

    ChatRoom room = chatRoomRepository.findByReportReviewId(reportReviewId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
    if (created) {
      appendSystemMessage(room, CONSULT_OPENED_SYSTEM_MESSAGE);
    }
    return new ConsultationRoomResult(room.getId(), created);
  }

  /** SYSTEM 안내 메시지를 저장·미리보기 갱신하고 커밋 후 브로드캐스트한다. */
  private void appendSystemMessage(ChatRoom room, String text) {
    ChatMessage system = chatMessageRepository.save(ChatMessage.system(room.getId(), text));
    room.touchLastMessage(text, system.getCreatedAt());
    chatEventPublisher.publishAfterCommit(new ChatBroadcastMessage(
        room.getId(), system.getId(), null, ChatMessageType.SYSTEM.name(), text, List.of(),
        system.getCreatedAt()));
  }
}
