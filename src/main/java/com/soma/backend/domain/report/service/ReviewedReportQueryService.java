package com.soma.backend.domain.report.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.ReviewedReportListResponse;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.ReviewedReportRow;
import com.soma.backend.domain.report.repository.StatusCount;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;

/** API#5 내 검수 내역 + 상단 통계 조회 전용 유스케이스(CQRS). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewedReportQueryService {

  private final ReportReviewRepository reportReviewRepository;

  public ReviewedReportListResponse.Stats getStats(UUID adjusterId, String month) {
    YearMonth targetMonth = parseMonth(month);
    LocalDateTime monthFrom = targetMonth.atDay(1).atStartOfDay();
    LocalDateTime monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    YearMonth previousMonth = targetMonth.minusMonths(1);
    LocalDateTime previousFrom = previousMonth.atDay(1).atStartOfDay();
    LocalDateTime previousTo = targetMonth.atDay(1).atStartOfDay();

    long monthlyReviewCount =
        reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, monthFrom, monthTo);
    long previousMonthReviewCount =
        reportReviewRepository.countByAdjusterIdAndCreatedAtBetween(adjusterId, previousFrom, previousTo);
    long totalCount = reportReviewRepository.countByAdjusterId(adjusterId);
    // NOTE(m1): consultationConvertedCount·conversionRate는 report_reviews.status=COUNSELING 기준이다.
    // 검수 반영(ReportReviewCommandService)은 status를 SENT로만 만들고, COUNSELING 전이는 고객이 제안의
    // 상담을 수락할 때(PATCH /reports/{reportId}/proposals/{proposalId}, status=ACCEPTED) 채팅방 개설과
    // 함께 일어난다 — 그 API가 생기면서 이 두 지표는 실제 값을 갖는다.
    long consultationConvertedCount = reportReviewRepository.countConsultationConvertedByAdjusterId(adjusterId);
    double conversionRate = totalCount == 0L ? 0.0 : (double) consultationConvertedCount / totalCount;

    return new ReviewedReportListResponse.Stats(
        monthlyReviewCount, previousMonthReviewCount, consultationConvertedCount, conversionRate, totalCount);
  }

  public ReviewedReportListResponse getReviewedReports(
      UUID adjusterId, String status, String month, Pageable pageable) {
    ReviewedReportListResponse.Stats stats = getStats(adjusterId, month);

    ReviewStatus outcome = parseStatusFilter(status);
    LocalDateTime monthFrom = null;
    LocalDateTime monthTo = null;
    if (StringUtils.hasText(month)) {
      YearMonth targetMonth = parseMonth(month);
      monthFrom = targetMonth.atDay(1).atStartOfDay();
      monthTo = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
    }
    Map<String, Integer> statusCounts = buildStatusCounts(adjusterId, monthFrom, monthTo);
    Page<ReviewedReportRow> rows =
        reportReviewRepository.findReviewedReportRows(adjusterId, outcome, monthFrom, monthTo, pageable);
    return ReviewedReportListResponse.from(stats, statusCounts, rows);
  }

  /**
   * 필터 탭 배지용 상태별 건수 맵을 만든다. {@code total} 다음 모든 {@link ReviewStatus} 키를 0으로 초기화해
   * 건수가 없는 탭도 항상 배지 값을 갖게 하고, DB 그룹 집계로 실제 값을 채운다. 삽입 순서(total → enum 순)를
   * 유지하려고 {@link LinkedHashMap}을 쓴다.
   */
  private Map<String, Integer> buildStatusCounts(UUID adjusterId, LocalDateTime monthFrom, LocalDateTime monthTo) {
    List<StatusCount> grouped = reportReviewRepository.countByStatusGrouped(adjusterId, monthFrom, monthTo);
    int total = (int) grouped.stream().mapToLong(row -> row.count()).sum();

    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("total", total);
    for (ReviewStatus reviewStatus : ReviewStatus.values()) {
      counts.put(reviewStatus.name(), 0);
    }
    for (StatusCount row : grouped) {
      counts.put(row.status().name(), row.count().intValue());
    }
    return counts;
  }

  private ReviewStatus parseStatusFilter(String status) {
    if (!StringUtils.hasText(status) || "ALL".equalsIgnoreCase(status)) {
      return null;
    }
    try {
      return ReviewStatus.valueOf(status);
    } catch (IllegalArgumentException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }

  private YearMonth parseMonth(String month) {
    if (!StringUtils.hasText(month)) {
      return YearMonth.now();
    }
    try {
      YearMonth parsed = YearMonth.parse(month);
      if (parsed.isAfter(YearMonth.now())) {
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
      }
      return parsed;
    } catch (DateTimeParseException ex) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
  }
}
