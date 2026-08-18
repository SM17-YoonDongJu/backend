package com.soma.backend.domain.report.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** ReportReview Aggregate 상태 전이(제안 채택/거절) 단위 테스트. */
class ReportReviewTest {

  private static ReportReview reviewWithStatus(ReviewStatus status) {
    ReportReview review = new ReportReview(UUID.randomUUID(), UUID.randomUUID());
    ReflectionTestUtils.setField(review, "status", status);
    return review;
  }

  @Test
  @DisplayName("검수 등록 직후 제안 상태는 SENT다")
  void newReviewStartsAsSent() {
    ReportReview review = new ReportReview(UUID.randomUUID(), UUID.randomUUID());

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.SENT);
  }

  @Test
  @DisplayName("accept: SENT 제안을 바로 채택하면 ACCEPTED로 전이된다")
  void acceptFromSent() {
    ReportReview review = reviewWithStatus(ReviewStatus.SENT);

    review.accept();

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
  }

  @Test
  @DisplayName("reject: SENT 제안을 바로 거절하면 REJECTED로 전이된다")
  void rejectFromSent() {
    ReportReview review = reviewWithStatus(ReviewStatus.SENT);

    review.reject();

    assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
  }

  @Test
  @DisplayName("accept: 이미 채택(ACCEPTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void acceptRejectsWhenAlreadyAccepted() {
    ReportReview review = reviewWithStatus(ReviewStatus.ACCEPTED);

    assertThatThrownBy(review::accept)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("reject: 이미 거절(REJECTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void rejectRejectsWhenAlreadyRejected() {
    ReportReview review = reviewWithStatus(ReviewStatus.REJECTED);

    assertThatThrownBy(review::reject)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
  }

  @Test
  @DisplayName("isDecidable: 종료 상태(ACCEPTED/REJECTED)만 재결정 불가, SENT는 가능")
  void isDecidableReflectsTerminalState() {
    assertThat(reviewWithStatus(ReviewStatus.SENT).isDecidable()).isTrue();
    assertThat(reviewWithStatus(ReviewStatus.COUNSELING).isDecidable()).isTrue();
    assertThat(reviewWithStatus(ReviewStatus.ACCEPTED).isDecidable()).isFalse();
    assertThat(reviewWithStatus(ReviewStatus.REJECTED).isDecidable()).isFalse();
  }

  @Test
  @DisplayName("startCounseling: SENT 제안으로 상담을 시작하면 COUNSELING으로 전이된다")
  void startCounselingFromSent() {
    // Given
    ReportReview review = reviewWithStatus(ReviewStatus.SENT);

    // When
    review.startCounseling();

    // Then
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.COUNSELING);
  }

  @Test
  @DisplayName("startCounseling: 이미 COUNSELING이면 멱등 no-op이다(더블클릭·재요청에서 같은 방 재사용)")
  void startCounselingIsIdempotentWhenAlreadyCounseling() {
    // Given
    ReportReview review = reviewWithStatus(ReviewStatus.COUNSELING);

    // When
    review.startCounseling();

    // Then
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.COUNSELING);
  }

  @Test
  @DisplayName("startCounseling: 이미 채택(ACCEPTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void startCounselingRejectsWhenAlreadyAccepted() {
    // Given
    ReportReview review = reviewWithStatus(ReviewStatus.ACCEPTED);

    // When & Then
    assertThatThrownBy(review::startCounseling)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
  }

  @Test
  @DisplayName("startCounseling: 이미 거절(REJECTED)된 제안이면 409 INVALID_STATE_TRANSITION")
  void startCounselingRejectsWhenAlreadyRejected() {
    // Given
    ReportReview review = reviewWithStatus(ReviewStatus.REJECTED);

    // When & Then
    assertThatThrownBy(review::startCounseling)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
  }

  @Test
  @DisplayName("상담 시작(COUNSELING) 이후에도 채택·거절이 가능하다 — COUNSELING은 결정 전 상태다")
  void counselingStillAllowsAcceptAndReject() {
    // Given
    ReportReview toAccept = reviewWithStatus(ReviewStatus.SENT);
    ReportReview toReject = reviewWithStatus(ReviewStatus.SENT);
    toAccept.startCounseling();
    toReject.startCounseling();

    // When
    toAccept.accept();
    toReject.reject();

    // Then
    assertThat(toAccept.getStatus()).isEqualTo(ReviewStatus.ACCEPTED);
    assertThat(toReject.getStatus()).isEqualTo(ReviewStatus.REJECTED);
  }
}
