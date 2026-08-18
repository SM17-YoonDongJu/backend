package com.soma.backend.domain.report.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import com.soma.backend.domain.report.dto.CreateReportRequest;
import com.soma.backend.domain.report.dto.CreateReportResponse;
import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.ProposalDecisionRequest;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.dto.ReportAnalysisStatusResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.service.ProposalQueryService;
import com.soma.backend.domain.report.service.ReportAnalysisStatusQueryService;
import com.soma.backend.domain.report.service.ReportCommandService;
import com.soma.backend.domain.report.service.ReportQueryService;
import com.soma.backend.global.response.ApiResponse;
import com.soma.backend.global.security.CustomUserDetails;

/**
 * 고객(user) 리포트 플로우 API(design.md §1, §6). 인가는 SecurityConfig(anyRequest().authenticated())의
 * 로그인 필수 + 서비스 레이어 소유 검증(design.md §8)이 담당한다. userId는 {@code @AuthenticationPrincipal}로
 * 주입하며, 인증 필수 경로이므로 principal은 non-null이다.
 * (목록 조회 GET /reports는 소유자 스코프로 본인 리포트를 전 상태 반환한다 — FE 대시보드·무한스크롤·마이페이지
 * 계약에 맞춰 복원. #106의 '타인 미검수 리포트 차단' 취지와는 상충하지 않는다.)
 * (상세 조회 GET /reports/{reportId}는 고객·사정사 공용 상세 shape을 반환한다 — 소유자 또는 사정사만 조회
 * 가능하며, 인가는 서비스 레이어가 판정한다. 파트너 앱의 draft-preview 부분집합도 이 shape에 포함된다.)
 */
@RestController
@RequiredArgsConstructor
public class ReportController {

  private final ReportCommandService reportCommandService;
  private final ReportQueryService reportQueryService;
  private final ProposalQueryService proposalQueryService;
  private final ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;

  /**
   * 사건 정보 입력
   */
  @PostMapping("/reports")
  public ResponseEntity<ApiResponse<CreateReportResponse>> create(
      @AuthenticationPrincipal CustomUserDetails principal, @RequestBody CreateReportRequest request) {
    CreateReportResponse data = reportCommandService.createReport(principal.getUserId(), request);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(ApiResponse.accepted("리포트 생성을 시작했습니다.", data));
  }

  /**
   * 고객 본인 리포트 목록 조회(소유자 스코프, 전 상태). status는 옵션 필터, page는 1-based.
   */
  @GetMapping("/reports")
  public ResponseEntity<ApiResponse<ReportCardListResponse>> list(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    ReportCardListResponse data = reportQueryService.getUserReports(principal.getUserId(), status, page, size);
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  /**
   * 받은 제안 목록 조회(GET /me/received-proposals). 제안 받은(REJECTED 제외) 리뷰를 per-review 카드 목록으로
   * 반환한다(응답 shape는 GET /reports와 동일). page는 1-based.
   */
  @GetMapping("/me/received-proposals")
  public ResponseEntity<ApiResponse<ReportCardListResponse>> receivedProposals(
      @AuthenticationPrincipal CustomUserDetails principal,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    ReportCardListResponse data = reportQueryService.getReceivedProposals(principal.getUserId(), page, size);
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  /**
   * 고객 리포트 상세 조회(고객·사정사 공용 shape). 소유자(USER) 또는 사정사만 조회 가능(그 외 403), 없으면 404.
   */
  @GetMapping("/reports/{reportId}")
  public ResponseEntity<ApiResponse<CustomerReportDetailResponse>> detail(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID reportId) {
    CustomerReportDetailResponse data =
        reportQueryService.getReportDetail(principal.getUserId(), principal.getRole(), reportId);
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  /**
   * 리포트 분석(OCR·AI) 처리 상태 조회. POST /reports가 202로 끝나므로 FE가 결과를 폴링하는 얇은 엔드포인트다.
   *
   * <p>인가는 상세 조회와 달리 <b>소유자 전용</b>이다(사정사 포함 그 외 403, 없으면 404). 실패 문서 파일명이
   * 실리는 유일한 응답이라 최소 권한으로 닫았다 — 근거는 {@code ReportAnalysisStatusQueryService}
   * javadoc(design.md §12 S3). 사정사에게 필요한 분석 상태는 목록·상세의 평면 3필드로 전달된다.
   */
  @GetMapping("/reports/{reportId}/analysis-status")
  public ResponseEntity<ApiResponse<ReportAnalysisStatusResponse>> analysisStatus(
      @AuthenticationPrincipal CustomUserDetails principal, @PathVariable UUID reportId) {
    ReportAnalysisStatusResponse data =
        reportAnalysisStatusQueryService.getAnalysisStatus(principal.getUserId(), reportId);
    return ResponseEntity.ok(ApiResponse.ok(data));
  }

  /**
   * 검수+제안 목록 조회
   */
  @GetMapping("/reports/{reportId}/proposals")
  public ResponseEntity<ApiResponse<ProposalListResponse>> proposals(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID reportId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(
        ApiResponse.ok(proposalQueryService.getProposals(principal.getUserId(), reportId, page, size)));
  }

  /**
   * 제안 상담 수락(ACCEPTED)/거절(REJECTED, 현재 미동작)
   */
  @PatchMapping("/reports/{reportId}/proposals/{proposalId}")
  public ResponseEntity<ApiResponse<ProposalDecisionResponse>> decide(
      @AuthenticationPrincipal CustomUserDetails principal,
      @PathVariable UUID reportId,
      @PathVariable UUID proposalId,
      @RequestBody ProposalDecisionRequest request) {
    ProposalDecisionResponse data =
        reportCommandService.decide(principal.getUserId(), reportId, proposalId, request.status());
    return ResponseEntity.ok(ApiResponse.ok(data));
  }
}
