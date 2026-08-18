package com.soma.backend.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * ChatRoom Aggregate 상담 방 개설 팩터리({@code openConsultation}) 단위 테스트(design.md §8-6 #9).
 * 파이프라인 방의 불변식(식별자 4개 필수 · ACTIVE 시작 · 이후 상담 결정 가능)을 검증한다.
 */
class ChatRoomTest {

  private final UUID userId = UUID.randomUUID();
  private final UUID adjusterId = UUID.randomUUID();
  private final UUID reportId = UUID.randomUUID();
  private final UUID reportReviewId = UUID.randomUUID();

  @Test
  @DisplayName("openConsultation: 식별자 4개를 채우고 status=ACTIVE인 방이 만들어진다(id는 저장 시 부여)")
  void openConsultationSetsPipelineFieldsAndActiveStatus() {
    // When
    ChatRoom room = ChatRoom.openConsultation(userId, adjusterId, reportId, reportReviewId);

    // Then
    assertThat(room.getUserId()).isEqualTo(userId);
    assertThat(room.getAdjusterId()).isEqualTo(adjusterId);
    assertThat(room.getReportId()).isEqualTo(reportId);
    assertThat(room.getReportReviewId()).isEqualTo(reportReviewId);
    assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.ACTIVE);
    assertThat(room.getId()).isNull();
    assertThat(room.getLastMessage()).isNull();
    assertThat(room.getLastMessageAt()).isNull();
  }

  @Test
  @DisplayName("openConsultation으로 연 방은 공유 리포트를 갖고 상담 수락/거절이 가능하다")
  void openConsultationRoomIsConsultable() {
    // When
    ChatRoom room = ChatRoom.openConsultation(userId, adjusterId, reportId, reportReviewId);

    // Then
    assertThat(room.hasSharedReport()).isTrue();
    assertThat(room.canDecideConsultation()).isTrue();
    assertThat(room.isMember(userId)).isTrue();
    assertThat(room.isMember(adjusterId)).isTrue();
    assertThat(room.isOwnedBy(userId)).isTrue();
    assertThat(room.isOwnedBy(adjusterId)).isFalse();
  }

  @Test
  @DisplayName("openConsultation: userId가 null이면 400 MISSING_REQUIRED_FIELD")
  void openConsultationRejectsNullUserId() {
    assertThatThrownBy(() -> ChatRoom.openConsultation(null, adjusterId, reportId, reportReviewId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("openConsultation: adjusterId가 null이면 400 MISSING_REQUIRED_FIELD")
  void openConsultationRejectsNullAdjusterId() {
    assertThatThrownBy(() -> ChatRoom.openConsultation(userId, null, reportId, reportReviewId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("openConsultation: reportId가 null이면 400 MISSING_REQUIRED_FIELD(상담 결정 불가 방 방지)")
  void openConsultationRejectsNullReportId() {
    assertThatThrownBy(() -> ChatRoom.openConsultation(userId, adjusterId, null, reportReviewId))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("openConsultation: reportReviewId가 null이면 400 MISSING_REQUIRED_FIELD(상담 결정 불가 방 방지)")
  void openConsultationRejectsNullReportReviewId() {
    assertThatThrownBy(() -> ChatRoom.openConsultation(userId, adjusterId, reportId, null))
        .isInstanceOfSatisfying(BusinessException.class,
            ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD));
  }

  @Test
  @DisplayName("close: 개설된 방을 종료하면 status가 CLOSED가 된다")
  void closeTransitionsToClosed() {
    // Given
    ChatRoom room = ChatRoom.openConsultation(userId, adjusterId, reportId, reportReviewId);

    // When
    room.close();

    // Then
    assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
  }
}
