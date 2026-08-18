package com.soma.backend.domain.chat.service;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.chat.entity.ChatRoom;
import com.soma.backend.domain.chat.repository.ChatRoomRepository;

/**
 * 상담 방(제안당 1개) 최초 INSERT 전용. 별도 트랜잭션(REQUIRES_NEW)에서 저장하므로 동시 최초 개설로
 * 부분 UNIQUE 인덱스({@code uk_chatroom_report_review_id}, V44)가 충돌해도 이 트랜잭션만 롤백되고
 * 호출자 트랜잭션은 오염되지 않는다({@code ReportReviewSkeletonInitializer}와 같은 패턴).
 *
 * <p>별도 Bean으로 분리한 이유는 전파 때문이다 — 호출자와 같은 클래스에 두면 자가 호출(self-invocation)이라
 * Spring AOP 프록시를 타지 않아 REQUIRES_NEW가 조용히 무시된다.
 *
 * <p>SYSTEM 안내 메시지는 여기서 남기지 않는다. 이 트랜잭션은 즉시 커밋되므로(브로드캐스트도 즉시 발행)
 * 안내 메시지까지 묶으면 바깥 트랜잭션(제안·리포트 상태 전이)이 뒤이어 실패했을 때 "방·안내 메시지는
 * 있는데 상태 전이는 없다"가 된다. 격리하는 것은 방 INSERT뿐이고, 안내 메시지·미리보기 갱신은 호출자의
 * 트랜잭션에 남긴다.
 */
@Component
@RequiredArgsConstructor
public class ChatRoomInsertOperator {

  private final ChatRoomRepository chatRoomRepository;

  /**
   * 제안에 연결된 상담 방을 INSERT 한다. 선조회로 이미 없음을 확인한 호출자만 부른다.
   *
   * @return 이번 호출로 방을 새로 만들었으면 {@code true}
   * @throws org.springframework.dao.DataIntegrityViolationException 동시 최초 개설 경쟁에서 져
   *     (report_review_id) UNIQUE가 충돌했을 때 — 호출자가 잡아 승자의 방을 다시 조회한다
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean insertConsultationRoom(UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId) {
    chatRoomRepository.saveAndFlush(ChatRoom.openConsultation(userId, adjusterId, reportId, reportReviewId));
    return true;
  }
}
