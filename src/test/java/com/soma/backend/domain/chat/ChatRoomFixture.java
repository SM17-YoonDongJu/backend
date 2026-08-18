package com.soma.backend.domain.chat;

import java.util.UUID;

import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.entity.ChatRoomStatus;

/**
 * {@link ChatRoom}에는 상담 방 개설 팩터리({@code openConsultation})가 있지만, 테스트 픽스처는 CLOSED 방·
 * 검색 방(reportId·reportReviewId 없음)처럼 팩터리가 허용하지 않는 임의 상태도 만들어야 한다.
 * 그래서 protected 기본 생성자를 리플렉션으로 인스턴스화하고 필드를 직접 채운다.
 *
 * <p>단위 테스트(Mockito)는 {@link #assignId}로 id를 직접 부여해 응답 DTO 조립에 쓰고,
 * 통합 테스트(@SpringBootTest 실제 test_db)는 id를 비워둔 채 저장해 {@code @GeneratedValue}가
 * 채우게 한다.
 */
public final class ChatRoomFixture {

  private ChatRoomFixture() {
  }

  /** id를 비운 신규 ChatRoom(저장 전 상태). 검색 방(reportId·reportReviewId 없음)은 둘 다 null로 넘긴다. */
  public static ChatRoom build(
      UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId, ChatRoomStatus status) {
    ChatRoom room = BeanUtils.instantiateClass(ChatRoom.class);
    ReflectionTestUtils.setField(room, "userId", userId);
    ReflectionTestUtils.setField(room, "adjusterId", adjusterId);
    ReflectionTestUtils.setField(room, "reportId", reportId);
    ReflectionTestUtils.setField(room, "reportReviewId", reportReviewId);
    ReflectionTestUtils.setField(room, "status", status);
    return room;
  }

  /** 단위 테스트에서 저장 없이 id를 직접 부여할 때 사용한다. */
  public static ChatRoom withId(
      UUID id, UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId, ChatRoomStatus status) {
    ChatRoom room = build(userId, adjusterId, reportId, reportReviewId, status);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }
}
