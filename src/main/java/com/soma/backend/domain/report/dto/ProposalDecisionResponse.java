package com.soma.backend.domain.report.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;

/** PATCH /reports/{reportId}/proposals/{proposalId} 응답(design.md §6). */
public record ProposalDecisionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adjusterId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReportStatus reportStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewStatus reviewStatus,
    @Schema(nullable = true,
        description = "status=ACCEPTED 요청으로 개설(또는 재사용)된 상담 채팅방 id. "
            + "REJECTED 요청에서는 항상 null이다.")
    UUID chatRoomId) {
}
