package com.soma.backend.domain.report.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.soma.backend.domain.report.dto.CustomerReportDetailResponse;
import com.soma.backend.domain.report.dto.Pagination;
import com.soma.backend.domain.report.dto.ProposalDecisionResponse;
import com.soma.backend.domain.report.dto.ProposalListResponse;
import com.soma.backend.domain.report.dto.ReportCardListResponse;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.ReviewStatus;
import com.soma.backend.domain.report.service.ProposalQueryService;
import com.soma.backend.domain.report.service.ReportAnalysisStatusQueryService;
import com.soma.backend.domain.report.service.ReportCommandService;
import com.soma.backend.domain.report.service.ReportQueryService;
import com.soma.backend.global.exception.BusinessException;
import com.soma.backend.global.exception.ErrorCode;
import com.soma.backend.global.security.CookieProvider;
import com.soma.backend.global.security.CustomUserDetails;
import com.soma.backend.global.security.JwtFilter;
import com.soma.backend.global.security.JwtProvider;
import com.soma.backend.global.security.RestAccessDeniedHandler;
import com.soma.backend.global.security.RestAuthenticationEntryPoint;
import com.soma.backend.global.security.SecurityConfig;
import com.soma.backend.infra.redis.TokenBlacklistRepository;

/**
 * ReportController 슬라이스 테스트. 인증(401)·로그인 통과(200)를 실제 SecurityFilterChain으로 검증한다
 * (@AuthenticationPrincipal + SecurityConfig anyRequest().authenticated()). 미인증 401은 필터가 처리한다.
 */
@WebMvcTest(ReportController.class)
@ActiveProfiles("test")
@Import({
    SecurityConfig.class,
    JwtFilter.class,
    JwtProvider.class,
    CookieProvider.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class
})
@DisplayName("ReportController 슬라이스 테스트")
class ReportControllerAuthTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ReportCommandService reportCommandService;

  @MockitoBean
  private ReportQueryService reportQueryService;

  @MockitoBean
  private ProposalQueryService proposalQueryService;

  @MockitoBean
  private ReportAnalysisStatusQueryService reportAnalysisStatusQueryService;

  @MockitoBean
  private TokenBlacklistRepository tokenBlacklistRepository;

  private RequestPostProcessor authenticatedAs(UUID userId) {
    CustomUserDetails principal = new CustomUserDetails(userId, "USER");
    return authentication(
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
  }

  @Test
  @DisplayName("비로그인이면 GET /reports/{id}/proposals는 401 LOGIN_REQUIRED")
  void unauthenticatedReturns401() throws Exception {
    mockMvc.perform(get("/reports/{id}/proposals", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("로그인 사용자는 GET /reports/{id}/proposals 200")
  void authenticatedProposalsReturns200() throws Exception {
    given(proposalQueryService.getProposals(any(), any(), anyInt(), anyInt()))
        .willReturn(new ProposalListResponse(List.of(), new Pagination(1, 10, 0, 0, false)));

    mockMvc.perform(get("/reports/{id}/proposals", UUID.randomUUID())
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("경로변수 reportId가 비-UUID면 400 INVALID_REQUEST(500 아님)")
  void malformedUuidPathVariableReturns400() throws Exception {
    mockMvc.perform(get("/reports/{id}/proposals", "not-a-uuid")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("비로그인이면 GET /reports는 401 LOGIN_REQUIRED")
  void unauthenticatedListReturns401() throws Exception {
    mockMvc.perform(get("/reports"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("로그인 사용자는 GET /reports 200 — list·pagination을 FE 계약(snake_case)으로 직렬화한다")
  void authenticatedListReturns200() throws Exception {
    ReportCardListResponse.Card card = new ReportCardListResponse.Card(
        UUID.randomUUID(), "MATCHED", "traffic", "교통사고 리포트",
        LocalDateTime.of(2026, 7, 27, 10, 0), "20260727-1",
        1_000_000, 2_000_000, 3,
        LocalDateTime.of(2026, 7, 27, 12, 0), "홍사정",
        8_500_000, "후유장해",
        "COMPLETED", null, null);
    ReportCardListResponse response =
        new ReportCardListResponse(List.of(card), new Pagination(1, 10, 1, 1, false));
    given(reportQueryService.getUserReports(any(), any(), anyInt(), anyInt())).willReturn(response);

    mockMvc.perform(get("/reports").with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.list[0].report_id").value(card.reportId().toString()))
        .andExpect(jsonPath("$.data.list[0].status").value("MATCHED"))
        .andExpect(jsonPath("$.data.list[0].accident_type").value("traffic"))
        .andExpect(jsonPath("$.data.list[0].report_no").value("20260727-1"))
        .andExpect(jsonPath("$.data.list[0].claimed_min_amount").value(1_000_000))
        .andExpect(jsonPath("$.data.list[0].claimed_max_amount").value(2_000_000))
        .andExpect(jsonPath("$.data.list[0].proposal_count").value(3))
        .andExpect(jsonPath("$.data.list[0].reviewed_at").exists())
        .andExpect(jsonPath("$.data.list[0].adjuster_nickname").value("홍사정"))
        .andExpect(jsonPath("$.data.list[0].offered_amount").value(8_500_000))
        .andExpect(jsonPath("$.data.list[0].treatment").value("후유장해"))
        .andExpect(jsonPath("$.data.pagination.page").value(1))
        .andExpect(jsonPath("$.data.pagination.size").value(10))
        .andExpect(jsonPath("$.data.pagination.total_elements").value(1))
        .andExpect(jsonPath("$.data.pagination.total_pages").value(1))
        .andExpect(jsonPath("$.data.pagination.has_next").value(false));
  }

  @Test
  @DisplayName("검수 전(무리뷰) 카드는 reviewed_at·adjuster_nickname을 null로 내려준다")
  void listSerializesReviewlessCardWithNullReviewFields() throws Exception {
    ReportCardListResponse.Card card = new ReportCardListResponse.Card(
        UUID.randomUUID(), "AWAITING_INSPECTION", "traffic", "제목 없음",
        LocalDateTime.of(2026, 7, 27, 10, 0), "20260727-2",
        null, null, 0,
        null, null,
        null, null,
        "PROCESSING", null, null);
    ReportCardListResponse response =
        new ReportCardListResponse(List.of(card), new Pagination(1, 10, 1, 1, false));
    given(reportQueryService.getUserReports(any(), any(), anyInt(), anyInt())).willReturn(response);

    mockMvc.perform(get("/reports").with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.list[0].report_id").value(card.reportId().toString()))
        .andExpect(jsonPath("$.data.list[0].status").value("AWAITING_INSPECTION"))
        .andExpect(jsonPath("$.data.list[0].proposal_count").value(0))
        .andExpect(jsonPath("$.data.list[0].reviewed_at").value(nullValue()))
        .andExpect(jsonPath("$.data.list[0].adjuster_nickname").value(nullValue()))
        .andExpect(jsonPath("$.data.list[0].offered_amount").value(nullValue()))
        .andExpect(jsonPath("$.data.pagination.total_elements").value(1));
  }

  @Test
  @DisplayName("GET /reports는 status·page·size 쿼리 파라미터를 서비스로 그대로 전달한다(page는 1-based)")
  void listPassesQueryParamsToService() throws Exception {
    given(reportQueryService.getUserReports(any(), any(), anyInt(), anyInt()))
        .willReturn(new ReportCardListResponse(List.of(), new Pagination(2, 20, 0, 0, false)));

    mockMvc.perform(get("/reports")
            .param("status", "COUNSELING")
            .param("page", "2")
            .param("size", "20")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk());

    then(reportQueryService).should().getUserReports(any(), eq("COUNSELING"), eq(2), eq(20));
  }

  @Test
  @DisplayName("비로그인이면 GET /me/received-proposals는 401 LOGIN_REQUIRED")
  void unauthenticatedReceivedProposalsReturns401() throws Exception {
    mockMvc.perform(get("/me/received-proposals"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("로그인 사용자는 GET /me/received-proposals 200 — reportListSchema로 직렬화하고 page(1-based) 전달")
  void authenticatedReceivedProposalsReturns200() throws Exception {
    ReportCardListResponse.Card card = new ReportCardListResponse.Card(
        UUID.randomUUID(), "COUNSELING", "traffic", "교통사고 리포트",
        LocalDateTime.of(2026, 7, 27, 10, 0), "20260727-2",
        1_000_000, 2_000_000, 1,
        LocalDateTime.of(2026, 7, 27, 12, 0), "김사정",
        null, "후유장해",
        "COMPLETED", null, null);
    given(reportQueryService.getReceivedProposals(any(), anyInt(), anyInt()))
        .willReturn(new ReportCardListResponse(List.of(card), new Pagination(2, 10, 1, 1, false)));

    mockMvc.perform(get("/me/received-proposals")
            .param("page", "2")
            .with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.list[0].report_no").value("20260727-2"))
        .andExpect(jsonPath("$.data.list[0].adjuster_nickname").value("김사정"))
        .andExpect(jsonPath("$.data.pagination.page").value(2));

    then(reportQueryService).should().getReceivedProposals(any(), eq(2), eq(10));
  }

  @Test
  @DisplayName("비로그인이면 GET /reports/{id}는 401 LOGIN_REQUIRED")
  void unauthenticatedDetailReturns401() throws Exception {
    mockMvc.perform(get("/reports/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("소유자는 GET /reports/{id} 200 — 고객 상세 shape을 FE 계약(snake_case)으로 전수 직렬화한다")
  void ownerDetailReturns200() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID reportId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    CustomerReportDetailResponse.IssueItem issue = new CustomerReportDetailResponse.IssueItem(
        "장해등급 과소 산정 가능", "등급 재산정이 필요합니다(사정사 의견)", "TRUSTED",
        List.of("약관 제12조", "진단서"), 3_000_000);
    CustomerReportDetailResponse.Adjuster adjuster =
        new CustomerReportDetailResponse.Adjuster("홍사정", 7);
    CustomerReportDetailResponse response = new CustomerReportDetailResponse(
        reportId, "MATCHED", "disability", "3주 입원 후 통원",
        12_000_000, 18_000_000, 8_500_000,
        List.of("상해후유장해"), List.of("일상생활배상책임"), List.of("대법원 2019다12345"),
        List.of(issue), "후유장해 청구가 가능한가요?", adjusterId, "HIGH", "20260727-1",
        "검수 의견입니다", LocalDateTime.of(2026, 7, 27, 12, 0), adjuster,
        "COMPLETED", null, null);
    given(reportQueryService.getReportDetail(any(), any(), any())).willReturn(response);

    mockMvc.perform(get("/reports/{id}", reportId).with(authenticatedAs(userId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.status").value("MATCHED"))
        .andExpect(jsonPath("$.data.accident_type").value("disability"))
        .andExpect(jsonPath("$.data.treatment").value("3주 입원 후 통원"))
        .andExpect(jsonPath("$.data.claimed_min_amount").value(12_000_000))
        .andExpect(jsonPath("$.data.claimed_max_amount").value(18_000_000))
        .andExpect(jsonPath("$.data.offered_amount").value(8_500_000))
        .andExpect(jsonPath("$.data.applicable_guarantees[0]").value("상해후유장해"))
        .andExpect(jsonPath("$.data.omitted_special_contract[0]").value("일상생활배상책임"))
        .andExpect(jsonPath("$.data.basis_terms_precedents[0]").value("대법원 2019다12345"))
        .andExpect(jsonPath("$.data.issue[0].title").value("장해등급 과소 산정 가능"))
        .andExpect(jsonPath("$.data.issue[0].opinion").value("등급 재산정이 필요합니다(사정사 의견)"))
        .andExpect(jsonPath("$.data.issue[0].status").value("TRUSTED"))
        .andExpect(jsonPath("$.data.issue[0].tags[0]").value("약관 제12조"))
        .andExpect(jsonPath("$.data.issue[0].impact_amount").value(3_000_000))
        .andExpect(jsonPath("$.data.question").value("후유장해 청구가 가능한가요?"))
        .andExpect(jsonPath("$.data.adjuster_id").value(adjusterId.toString()))
        .andExpect(jsonPath("$.data.confidence_level").value("HIGH"))
        .andExpect(jsonPath("$.data.report_no").value("20260727-1"))
        .andExpect(jsonPath("$.data.review_comment").value("검수 의견입니다"))
        .andExpect(jsonPath("$.data.reviewed_at").exists())
        .andExpect(jsonPath("$.data.adjuster.nickname").value("홍사정"))
        .andExpect(jsonPath("$.data.adjuster.career").value(7));

    then(reportQueryService).should().getReportDetail(userId, "USER", reportId);
  }

  @Test
  @DisplayName("소유자도 사정사도 아니면 서비스가 던진 403 FORBIDDEN을 그대로 전달한다")
  void nonOwnerNonAdjusterDetailReturns403() throws Exception {
    given(reportQueryService.getReportDetail(any(), any(), any()))
        .willThrow(new BusinessException(ErrorCode.FORBIDDEN));

    mockMvc.perform(get("/reports/{id}", UUID.randomUUID()).with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("존재하지 않는 리포트면 서비스가 던진 404 REPORT_NOT_FOUND를 그대로 전달한다")
  void missingReportDetailReturns404() throws Exception {
    given(reportQueryService.getReportDetail(any(), any(), any()))
        .willThrow(new BusinessException(ErrorCode.REPORT_NOT_FOUND));

    mockMvc.perform(get("/reports/{id}", UUID.randomUUID()).with(authenticatedAs(UUID.randomUUID())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
  }

  // -----------------------------------------------------------------------------------------
  // PATCH /reports/{reportId}/proposals/{proposalId} — 제안 상담 수락 (design.md §8-6 #14~#16)
  // status=ACCEPTED는 "상담 수락"(채팅방 개설)이라 응답 review_status는 COUNSELING이다.
  // REJECTED는 현재 no-op이며 최종 채택·거절은 PATCH /chats/{chatRoomId}/accept·reject 전용이다.
  // -----------------------------------------------------------------------------------------

  private String decideBody(String status) {
    return "{\"status\":\"" + status + "\"}";
  }

  @Test
  @DisplayName("status=ACCEPTED면 200 + data.chat_room_id(snake_case)를 non-null로 내려준다")
  void decideAcceptedReturnsChatRoomId() throws Exception {
    UUID reportId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    UUID adjusterId = UUID.randomUUID();
    UUID chatRoomId = UUID.randomUUID();
    given(reportCommandService.decide(any(), any(), any(), eq("ACCEPTED")))
        .willReturn(new ProposalDecisionResponse(reportId, proposalId, adjusterId,
            ReportStatus.COUNSELING, ReviewStatus.COUNSELING, chatRoomId));

    mockMvc.perform(patch("/reports/{id}/proposals/{pid}", reportId, proposalId)
            .with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(decideBody("ACCEPTED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("200"))
        .andExpect(jsonPath("$.data.report_id").value(reportId.toString()))
        .andExpect(jsonPath("$.data.proposal_id").value(proposalId.toString()))
        .andExpect(jsonPath("$.data.adjuster_id").value(adjusterId.toString()))
        .andExpect(jsonPath("$.data.report_status").value("COUNSELING"))
        .andExpect(jsonPath("$.data.review_status").value("COUNSELING"))
        .andExpect(jsonPath("$.data.chat_room_id").value(chatRoomId.toString()));
  }

  @Test
  @DisplayName("status=REJECTED면 200이고 상태는 그대로·data.chat_room_id는 null이다(no-op)")
  void decideRejectedReturnsNullChatRoomId() throws Exception {
    UUID reportId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    given(reportCommandService.decide(any(), any(), any(), eq("REJECTED")))
        .willReturn(new ProposalDecisionResponse(reportId, proposalId, UUID.randomUUID(),
            ReportStatus.AWAITING_ADOPTION, ReviewStatus.SENT, null));

    mockMvc.perform(patch("/reports/{id}/proposals/{pid}", reportId, proposalId)
            .with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(decideBody("REJECTED")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.report_status").value("AWAITING_ADOPTION"))
        .andExpect(jsonPath("$.data.review_status").value("SENT"))
        .andExpect(jsonPath("$.data.chat_room_id").value(nullValue()));
  }

  @Test
  @DisplayName("비소유자가 상담을 수락하려 하면 서비스가 던진 403 FORBIDDEN을 그대로 전달한다")
  void decideByNonOwnerReturns403() throws Exception {
    given(reportCommandService.decide(any(), any(), any(), any()))
        .willThrow(new BusinessException(ErrorCode.FORBIDDEN));

    mockMvc.perform(patch("/reports/{id}/proposals/{pid}", UUID.randomUUID(), UUID.randomUUID())
            .with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(decideBody("ACCEPTED")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("비로그인이면 PATCH /reports/{id}/proposals/{pid}는 401 LOGIN_REQUIRED")
  void unauthenticatedDecideReturns401() throws Exception {
    mockMvc.perform(patch("/reports/{id}/proposals/{pid}", UUID.randomUUID(), UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(decideBody("ACCEPTED")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  @DisplayName("제안이 이미 종료 상태면 서비스가 던진 409 INVALID_STATE_TRANSITION을 그대로 전달한다")
  void decideOnTerminalProposalReturns409() throws Exception {
    given(reportCommandService.decide(any(), any(), any(), any()))
        .willThrow(new BusinessException(ErrorCode.INVALID_STATE_TRANSITION));

    mockMvc.perform(patch("/reports/{id}/proposals/{pid}", UUID.randomUUID(), UUID.randomUUID())
            .with(authenticatedAs(UUID.randomUUID()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(decideBody("ACCEPTED")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }
}
