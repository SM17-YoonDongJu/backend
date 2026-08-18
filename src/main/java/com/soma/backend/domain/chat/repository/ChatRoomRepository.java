package com.soma.backend.domain.chat.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.soma.backend.domain.chat.entity.ChatRoom;

/** ChatRoom Aggregate Spring Data JPA 리포지토리 + 목록 조회 QueryDSL 프래그먼트. */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, UUID>, ChatRoomRepositoryCustom {

  /** 같은 리포트에 딸린 방들(형제 방 종료용) — 상담 수락 시 다른 사정사 방을 CLOSED 처리한다. */
  List<ChatRoom> findByReportId(UUID reportId);

  /** 제안(REPORT_REVIEWS)에 연결된 상담 방 — 방 개설 멱등 확보에 사용한다. 제안당 최대 1개(UNIQUE, V43). */
  Optional<ChatRoom> findByReportReviewId(UUID reportReviewId);
}
