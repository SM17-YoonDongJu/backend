package com.soma.backend.domain.report.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.soma.backend.domain.common.entity.BaseEntity;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/**
 * REPORT_REVIEWS Aggregate Root — 사정사 검수 작업 공간(고객 노출). (report_id, adjuster_id) 당 1행(UK).
 * AI 초안(REPORTS)과 격리되어 사정사 수정본만 담는다(design.md A8, glossary §13).
 */
@Entity
@Table(name = "report_reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportReview extends BaseEntity {

  @Id
  @GeneratedValue
  private UUID id;

  @Column(name = "report_id", nullable = false)
  private UUID reportId;

  @Column(name = "adjuster_id", nullable = false)
  private UUID adjusterId;

  @Column(name = "review")
  private String review;

  @Column(name = "estimate_min_amount")
  private Integer estimateMinAmount;

  @Column(name = "estimate_max_amount")
  private Integer estimateMaxAmount;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "applicable_guarantees", columnDefinition = "text[]")
  private List<String> applicableGuarantees;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "omitted_special_contract", columnDefinition = "text[]")
  private List<String> omittedSpecialContract;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "basis_terms_precedents", columnDefinition = "text[]")
  private List<String> basisTermsPrecedents;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private ReviewStatus status;

  /** 사정사별 쟁점 검수 — 이 Aggregate가 소유·생명주기 관리(외부 Repository로 직접 저장/삭제하지 않음). */
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
  @JoinColumn(name = "report_review_id", nullable = false)
  private List<ReportReviewIssue> issues = new ArrayList<>();

  public ReportReview(UUID reportId, UUID adjusterId) {
    this.reportId = reportId;
    this.adjusterId = adjusterId;
    this.status = ReviewStatus.SENT;
  }

  /** 사용자가 이 제안을 채택. 리포트 종결과 함께 호출된다(design.md §6 decide). */
  public void accept() {
    ensureDecidable();
    this.status = ReviewStatus.ACCEPTED;
  }

  /** 사용자가 이 제안을 거절. 목록에서 제외된다(리포트 상태는 유지). */
  public void reject() {
    ensureDecidable();
    this.status = ReviewStatus.REJECTED;
  }

  /**
   * 고객이 이 제안으로 상담을 시작한다(채팅방 개설 트리거). SENT → COUNSELING.
   * 이미 COUNSELING이면 멱등 no-op이다 — 더블클릭·재요청에서 같은 방을 그대로 쓰기 위함.
   * 종료 상태(ACCEPTED/REJECTED)에서는 409 INVALID_STATE_TRANSITION.
   */
  public void startCounseling() {
    if (this.status == ReviewStatus.COUNSELING) {
      return;
    }
    ensureDecidable();
    this.status = ReviewStatus.COUNSELING;
  }

  /** 아직 결정 전(SENT·COUNSELING)이라 채택/거절 가능한지 — 종료 상태(ACCEPTED/REJECTED)면 false. */
  public boolean isDecidable() {
    return this.status != ReviewStatus.ACCEPTED && this.status != ReviewStatus.REJECTED;
  }

  /** 종료 상태(ACCEPTED/REJECTED)에서의 재결정을 차단 — CLOSED 리포트와 제안 상태 불일치를 막는다. */
  private void ensureDecidable() {
    if (!isDecidable()) {
      throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
    }
  }

  /**
   * 검수 내용 부분 갱신(estimate·배열3·review). null 인자는 미포함(기존 값 유지)으로 보고, 빈 배열·빈 문자열은
   * 명시적 비움으로 반영한다. status 전이와 무관하게 upsert된다.
   */
  public void updateReviewContent(Integer estimateMinAmount, Integer estimateMaxAmount,
      List<String> applicableGuarantees, List<String> omittedSpecialContract,
      List<String> basisTermsPrecedents, String review) {
    if (estimateMinAmount != null) {
      this.estimateMinAmount = estimateMinAmount;
    }
    if (estimateMaxAmount != null) {
      this.estimateMaxAmount = estimateMaxAmount;
    }
    if (applicableGuarantees != null) {
      this.applicableGuarantees = applicableGuarantees;
    }
    if (omittedSpecialContract != null) {
      this.omittedSpecialContract = omittedSpecialContract;
    }
    if (basisTermsPrecedents != null) {
      this.basisTermsPrecedents = basisTermsPrecedents;
    }
    if (review != null) {
      this.review = review;
    }
  }

  /**
   * 쟁점 1건 부분 반영(upsert, 삭제 없음). reviewIssueId(행 PK)가 있으면 그 행을, 없으면 reportIssueId(AI 쟁점)로
   * 매칭해 인플레이스 갱신하고, 매칭이 없으면 신규 추가한다. 보내지 않은 쟁점은 그대로 유지된다.
   */
  public void upsertIssue(UUID reviewIssueId, ReportReviewIssue desired) {
    ReportReviewIssue target = null;
    if (reviewIssueId != null) {
      target = findByReviewIssueId(reviewIssueId);
    }
    if (target == null && desired.getReportIssueId() != null) {
      target = findByReportIssueId(desired.getReportIssueId());
    }
    if (target == null) {
      this.issues.add(desired);
      return;
    }
    target.updateContent(desired.getTitle(), desired.getDescription(), desired.getImpactAmount(),
        desired.getReviewStatus(), desired.getAdjusterOpinion(), desired.getModifiedReason(),
        desired.getExcludedReason());
  }

  private ReportReviewIssue findByReviewIssueId(UUID reviewIssueId) {
    for (ReportReviewIssue issue : this.issues) {
      if (reviewIssueId.equals(issue.getId())) {
        return issue;
      }
    }
    return null;
  }

  private ReportReviewIssue findByReportIssueId(UUID reportIssueId) {
    for (ReportReviewIssue issue : this.issues) {
      if (reportIssueId.equals(issue.getReportIssueId())) {
        return issue;
      }
    }
    return null;
  }
}
