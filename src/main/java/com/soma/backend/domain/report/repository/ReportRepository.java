package com.soma.backend.domain.report.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportStatus;

/**
 * Report Aggregate Spring Data JPA 리포지토리 + 요약/카운트 조회.
 * 동적 목록 조회는 {@link ReportRepositoryCustom}(QueryDSL)에서 구현한다.
 */
public interface ReportRepository extends JpaRepository<Report, UUID>, ReportRepositoryCustom {

  List<Report> findAllByIdIn(List<UUID> ids);

  /**
   * 상담 거절 시 "다른 COUNSELING 제안이 없으면 채택 대기로 복귀"를 원자적으로 판정하기 위한 잠금 조회.
   *
   * <p>같은 리포트의 형제 상담방을 동시에 거절하면 두 트랜잭션이 서로를 아직 COUNSELING으로 관찰해
   * 둘 다 리포트 복귀를 건너뛰고, 리포트가 COUNSELING에 영구히 갇힌다(그 뒤로 COUNSELING을 벗어나는
   * 경로가 없다 — {@code ReportNotSelectionSweeper}도 COUNSELING은 스윕하지 않는다). 이 잠금이 두 번째
   * 거절 트랜잭션을 첫 번째가 커밋할 때까지 대기시켜 형제 상태를 정확히 관찰하게 한다.
   *
   * <p>거절 경로 전용이다 — 잠금이 필요 없는 조회는 {@code findById}를 그대로 쓴다.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM Report r WHERE r.id = :id")
  Optional<Report> findByIdForUpdate(@Param("id") UUID id);

  /**
   * BLOCKED 알림 스윕 대상 — reports를 직접 스캔한다(ai.ocr_job_failures 저널에는 흔적이 없어 조인 불필요).
   * 파생 쿼리로 충분한 단순 조회라 QueryDSL을 쓰지 않는다(하네스 쿼리 규칙).
   */
  List<Report> findAllByStatusAndBlockedNotifiedAtIsNull(ReportStatus status, Pageable pageable);

  /**
   * NEEDS_REUPLOAD(OCR 품질 미달) 알림 스윕 대상 — BLOCKED와 같은 이유로 reports를 직접 스캔한다
   * (품질 판정이라 ai.ocr_job_failures 저널에 흔적이 없어 조인 불필요). 멱등 가드 컬럼이 달라 BLOCKED
   * 조회와 합칠 수 없다 — 파생 쿼리는 컬럼명이 메서드 이름에 박혀 파라미터화가 불가능하다.
   * 파생 쿼리로 충분한 단순 조회라 QueryDSL을 쓰지 않는다(하네스 쿼리 규칙).
   */
  List<Report> findAllByStatusAndNeedsReuploadNotifiedAtIsNull(ReportStatus status, Pageable pageable);

  /**
   * 당일 case_no 시퀀스를 원자적으로 발급한다(1부터). ON CONFLICT DO UPDATE로 동시 요청에도 단일 행이
   * 원자 증가하므로, count-then-insert 경쟁으로 인한 case_no UNIQUE 위반(→500)을 원천 차단한다(ReportHold 관례).
   */
  @Query(value = "INSERT INTO report_case_sequences (day, seq) VALUES (:day, 1) "
      + "ON CONFLICT (day) DO UPDATE SET seq = report_case_sequences.seq + 1 "
      + "RETURNING seq", nativeQuery = true)
  int nextCaseNoSequence(@Param("day") LocalDate day);

  /**
   * 검수 대기 풀(AWAITING_INSPECTION + AWAITING_ADOPTION) 중 요청 사정사의 '내 대기 큐' 카운트 —
   * 본인이 보류(report_holds)하지도, 이미 검수 진행(report_reviews SENT·COUNSELING)하지도 않은 건수다.
   * 내가 SENT 한 AWAITING_ADOPTION 건은 진행중으로 넘어갔으므로 검수 대기에서 뺀다(진행중∩검수대기=∅).
   * 사정사별 개인화 카운트라 홈 풀(전역 정의)과 값이 다를 수 있다.
   */
  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status IN (com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION, "
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_ADOPTION) "
      + "AND NOT EXISTS (SELECT 1 FROM ReportHold h WHERE h.reportId = r.id AND h.adjusterId = :adjusterId) "
      + "AND NOT EXISTS (SELECT 1 FROM ReportReview rv WHERE rv.reportId = r.id AND rv.adjusterId = :adjusterId "
      + "AND rv.status IN (com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING))")
  long countPendingNotHeldBy(@Param("adjusterId") UUID adjusterId);

  /** 마이페이지 활동 집계 — 요청 사용자가 만든 리포트 총수(GET /users/me/activity-summary). */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.userId = :userId")
  long countByUserId(@Param("userId") UUID userId);

  /** 마이페이지 활동 집계 — 요청 사용자의 종결(CLOSED) 리포트 수. */
  @Query("SELECT COUNT(r) FROM Report r WHERE r.userId = :userId "
      + "AND r.status = com.soma.backend.domain.report.entity.ReportStatus.CLOSED")
  long countClosedByUserId(@Param("userId") UUID userId);

  /**
   * 미채택(NOT_SELECTED) 자동 전이 대상 — 지정 상태(검수 대기·채택 대기)로 threshold 이전에 접수된 리포트.
   * 이미 CLOSED·COUNSELING·NOT_SELECTED인 리포트는 sources에 없어 자연히 제외된다.
   */
  @Query("SELECT r FROM Report r WHERE r.status IN :sources AND r.createdAt < :threshold")
  List<Report> findExpiredForNotSelection(
      @Param("sources") Collection<ReportStatus> sources, @Param("threshold") LocalDateTime threshold);

  /** 마감 임박(AWAITING_INSPECTION·threshold 이전 접수) 중 본인이 보류·검수(SENT·COUNSELING)하지 않은 건수. */
  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status = com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION "
      + "AND r.createdAt <= :dueSoonThreshold "
      + "AND NOT EXISTS (SELECT 1 FROM ReportHold h WHERE h.reportId = r.id AND h.adjusterId = :adjusterId) "
      + "AND NOT EXISTS (SELECT 1 FROM ReportReview rv WHERE rv.reportId = r.id AND rv.adjusterId = :adjusterId "
      + "AND rv.status IN (com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING))")
  long countDueSoonNotHeldBy(
      @Param("dueSoonThreshold") LocalDateTime dueSoonThreshold, @Param("adjusterId") UUID adjusterId);

  /**
   * 검수 대기 풀 중 지정 사고유형(사정사 전문분야 매칭)에 해당하고, 본인이 보류·검수(SENT·COUNSELING)하지
   * 않은 건수. 요약의 '내 전문분야 매칭' 배지도 검수 대기와 같은 개인화 축(보류·내 검수 제외)에 맞춘다.
   */
  @Query("SELECT COUNT(r) FROM Report r "
      + "WHERE r.status IN (com.soma.backend.domain.report.entity.ReportStatus.AWAITING_INSPECTION, "
      + "com.soma.backend.domain.report.entity.ReportStatus.AWAITING_ADOPTION) "
      + "AND r.accidentType IN :types "
      + "AND NOT EXISTS (SELECT 1 FROM ReportHold h WHERE h.reportId = r.id AND h.adjusterId = :adjusterId) "
      + "AND NOT EXISTS (SELECT 1 FROM ReportReview rv WHERE rv.reportId = r.id AND rv.adjusterId = :adjusterId "
      + "AND rv.status IN (com.soma.backend.domain.report.entity.ReviewStatus.SENT, "
      + "com.soma.backend.domain.report.entity.ReviewStatus.COUNSELING))")
  long countPendingByAccidentTypeInNotHeldBy(
      @Param("types") Collection<AccidentType> types, @Param("adjusterId") UUID adjusterId);

  /*
   * 아직 엔티티로 모델링되지 않은 테이블을 조인하는 읽기 전용 projection이라 QueryDSL로 표현할 수 없다.
   * 하네스의 native query 금지 규칙에 대한 '문서화된 예외'로 유지한다(해당 도메인 모델링 시 QueryDSL로 전환):
   *   - findReviewContext : user_claims / insurance_products / insurers (미매핑)
   * diagnosis·hospitalization은 user_claims details(jsonb) 전환으로 컬럼에서 제거됨(추후 details 기반 노출).
   * region(text[])은 findRegionByReportId(QueryDSL)로 별도 조회하므로 여기서는 선택하지 않는다.
   * additional_information·description은 암호화(bytea)로 전환돼 컨버터가 적용되는 엔티티(UserClaim) 경유
   * 조회로 옮겼다 — native 결과에는 컨버터가 적용되지 않아 여기서 셀렉트하면 복호화되지 않은 봉투 바이트가 나온다.
   */
  @Query(value = "SELECT u.nickname AS nickname, u.gender AS gender, u.birth_date AS birthDate, "
      + "u.created_at AS joinedAt, "
      + "uc.accident_type AS claimAccidentType, uc.accident_date AS accidentDate, "
      + "ip.product_name AS productName, ins.name AS insurerName "
      + "FROM reports r "
      + "JOIN users u ON u.id = r.user_id "
      + "LEFT JOIN user_claims uc ON uc.id = r.claim_id "
      + "LEFT JOIN insurance_products ip ON ip.id = r.product_id "
      + "LEFT JOIN insurers ins ON ins.id = ip.insurer_id "
      + "WHERE r.id = :reportId",
      nativeQuery = true)
  ReviewContextRow findReviewContext(@Param("reportId") UUID reportId);
}
