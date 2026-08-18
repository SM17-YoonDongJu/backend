package com.soma.backend.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PATCH /reports/{reportId}/proposals/{proposalId} 요청(design.md §6).
 * status = ACCEPTED(상담 수락 — 채팅방 개설) | REJECTED(현재 아무 동작 없음).
 * 최종 채택·거절은 채팅방 화면(PATCH /chats/{chatRoomId}/accept·reject) 전용이다.
 */
public record ProposalDecisionRequest(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "ACCEPTED(상담 수락 — 채팅방 개설) 또는 REJECTED(현재 아무 동작 없음)만 허용. "
            + "ACCEPTED 요청의 응답 review_status는 ACCEPTED가 아니라 COUNSELING이다"
            + "(제안 최종 채택은 PATCH /chats/{chatRoomId}/accept 전용)")
    String status) {
}
