package com.soma.backend.domain.report.entity.event;

import java.util.UUID;

/**
 * 고객이 제안을 골라 상담을 시작했다는 도메인 사실 이벤트(CONSULT_REQUESTED). 수신자는 그 제안을 보낸 사정사다.
 *
 * <p>순수 값(VO)이라 식별자가 없고 Aggregate 간 참조는 객체가 아니라 UUID로만 담는다. report 컨텍스트가
 * "상담이 시작됐다"는 사실만 발행하고, 이 사실을 어떤 알림(문안·토글·푸시)으로 만들지는 notification
 * 리스너가 결정한다 — 그래서 이 record는 NotificationType/문안을 모른다(Spring/JPA import 0).
 *
 * @param adjusterId 수신자(제안을 보낸 사정사) — report_reviews.adjuster_id
 * @param userId     상담을 시작한 고객(리포트 소유자) — 향후 문안 확장 여지
 * @param reportId   딥링크·컨텍스트용 리포트 식별자
 * @param proposalId 상담 대상 제안(REPORT_REVIEWS.id)
 * @param chatRoomId 이번에 개설된 채팅방 — 푸시 딥링크 대상
 */
public record ConsultationRequestedEvent(
    UUID adjusterId, UUID userId, UUID reportId, UUID proposalId, UUID chatRoomId) {
}
