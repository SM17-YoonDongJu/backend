package com.soma.backend.domain.chat.dto;

import java.util.UUID;

/**
 * 상담 채팅방 개설 결과(서비스 간 내부 반환값). {@code created}는 이번 호출이 방을 새로 만들었는지를
 * 알려준다 — 호출자(report)가 "상담 요청 알림"을 최초 개설 때만 발행해 재요청 스팸을 막는 데 쓴다
 * ({@code ReportReviewCommandService}의 {@code proposalCreated} 게이팅과 같은 사고방식).
 *
 * <p>컨트롤러에 노출되지 않는 내부 전달용 타입이라 OpenAPI {@code @Schema} 대상이 아니다.
 */
public record ConsultationRoomResult(UUID chatRoomId, boolean created) {
}
