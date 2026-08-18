package com.soma.backend.domain.report.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 제안 결정 응답에 추가된 {@code chat_room_id}가 OpenAPI 문서에 계약대로 실렸는지 검증한다
 * (CLAUDE.md OpenAPI 규칙 + design.md §3-2).
 *
 * <p>이 필드는 status=ACCEPTED(상담 수락) 요청에서만 채워지는 조건부 필드다 — 따라서 {@code required}에
 * 들어가면 안 되고, OpenAPI 3.1 유니온 타입({@code "type": ["string","null"]})으로 nullable이어야 하며,
 * 표준으로 표현할 수 없는 "언제 채워지는가"는 {@code description}에 서술돼 있어야 한다. 프로퍼티명은 실제
 * 응답과 같은 snake_case여야 한다(springdoc 정적 매퍼 camelCase 드리프트 회귀 방지).
 */
@SpringBootTest(properties = "app.docs.public=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("제안 결정 OpenAPI 문서 테스트")
class ProposalDecisionOpenApiDocsTest {

  private static final String RESPONSE = "$.components.schemas.ProposalDecisionResponse";
  private static final String REQUEST = "$.components.schemas.ProposalDecisionRequest";

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("응답 스키마 — 기존 5필드는 required, 신규 chat_room_id는 required가 아니고 nullable이다")
  void decisionResponseSchemaDeclaresConditionalChatRoomId() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(RESPONSE + ".required",
            hasItems("report_id", "proposal_id", "adjuster_id", "report_status", "review_status")))
        .andExpect(jsonPath(RESPONSE + ".required", not(hasItem("chat_room_id"))))
        .andExpect(jsonPath(RESPONSE + ".properties.chat_room_id.type", hasItem("null")))
        .andExpect(jsonPath(RESPONSE + ".properties.chat_room_id.description",
            containsString("ACCEPTED")));
  }

  @Test
  @DisplayName("요청 스키마 — status description에 ACCEPTED·REJECTED가 허용값으로 문서화된다")
  void decisionRequestSchemaDocumentsAllowedValues() throws Exception {
    mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath(REQUEST + ".required", hasItem("status")))
        .andExpect(jsonPath(REQUEST + ".properties.status.description",
            containsString("ACCEPTED")))
        .andExpect(jsonPath(REQUEST + ".properties.status.description",
            containsString("REJECTED")));
  }
}
