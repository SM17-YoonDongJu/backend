package com.soma.backend.domain.chat.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * 상담 채팅방(Aggregate Root). 참여자는 user_id·adjuster_id 2인(1:1).
 *
 * <p>{@code report_review_id}(nullable)로 상담 대상 제안(공유 리포트)을 식별한다 — 파이프라인 경로만 set,
 * 사정사 검색 경로는 null. {@code status}는 방 생명주기(ACTIVE/CLOSED)만 나타내며, 수락/거절 결정은
 * REPORT_REVIEWS.status가 소유한다(중복 금지).
 */
@Entity
@Table(name = "chatroom")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Column(name = "report_id")
  private UUID reportId;

  @Column(name = "report_review_id")
  private UUID reportReviewId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20)
  private ChatRoomStatus status;

  @Column(name = "last_message")
  private String lastMessage;

  @Column(name = "last_message_at")
  private LocalDateTime lastMessageAt;

  @Column(name = "user_last_read_at")
  private LocalDateTime userLastReadAt;

  @Column(name = "adjuster_last_read_at")
  private LocalDateTime adjusterLastReadAt;

  /** 방 참여자(고객 또는 사정사) 여부 — 인가 가드. */
  public boolean isMember(UUID accountId) {
    return accountId != null && (accountId.equals(userId) || accountId.equals(adjusterId));
  }

  /** 방 소유자(상담을 요청한 고객) 여부 — 수락/거절 인가 가드. */
  public boolean isOwnedBy(UUID accountId) {
    return accountId != null && accountId.equals(userId);
  }

  /** 상대방 계정 id. me가 고객이면 사정사, 사정사면 고객. */
  public UUID counterpartOf(UUID me) {
    return me.equals(userId) ? adjusterId : userId;
  }

  /** 공유 리포트(사정사 검수 결과)가 연결된 파이프라인 방인지 — 검색 방은 false. */
  public boolean hasSharedReport() {
    return reportReviewId != null;
  }

  /** 상담 결정(수락/거절)이 가능한 파이프라인 방인지 — 제안·리포트가 모두 연결된 경우만. */
  public boolean canDecideConsultation() {
    return reportReviewId != null && reportId != null;
  }

  /** 내 읽음 커서(안읽음 계산용). */
  public LocalDateTime lastReadAtOf(UUID accountId) {
    return accountId.equals(userId) ? userLastReadAt : adjusterLastReadAt;
  }

  /** 읽음 커서를 갱신한다 — 본인 쪽만. 참여자가 아니면 CHAT_NOT_A_MEMBER. */
  public void markRead(UUID readerId, LocalDateTime at) {
    if (readerId.equals(userId)) {
      this.userLastReadAt = at;
    } else if (readerId.equals(adjusterId)) {
      this.adjusterLastReadAt = at;
    } else {
      throw new BusinessException(ErrorCode.CHAT_NOT_A_MEMBER);
    }
  }

  /** 마지막 메시지 미리보기·시각 비정규화 갱신(목록 정렬용). */
  public void touchLastMessage(String preview, LocalDateTime at) {
    this.lastMessage = preview;
    this.lastMessageAt = at;
  }

  /** 방을 종료 상태로 전이한다. */
  public void close() {
    this.status = ChatRoomStatus.CLOSED;
  }

  /**
   * 상담 채팅방을 개설한다(고객이 제안을 골라 상담을 시작하는 시점). 파이프라인 방이므로
   * reportId·reportReviewId가 모두 필수다 — 하나라도 비면 {@link #canDecideConsultation()}이 거짓이 되어
   * 이후 상담 수락/거절이 영구히 막힌다. 방 생명주기는 ACTIVE로 시작하며, 수락/거절 결정 상태는
   * 여기서 다루지 않는다(REPORT_REVIEWS.status가 소유).
   */
  public static ChatRoom openConsultation(UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    if (userId == null || adjusterId == null || reportId == null || reportReviewId == null) {
      throw new BusinessException(ErrorCode.MISSING_REQUIRED_FIELD);
    }
    ChatRoom room = new ChatRoom();
    room.userId = userId;
    room.adjusterId = adjusterId;
    room.reportId = reportId;
    room.reportReviewId = reportReviewId;
    room.status = ChatRoomStatus.ACTIVE;
    return room;
  }
}
