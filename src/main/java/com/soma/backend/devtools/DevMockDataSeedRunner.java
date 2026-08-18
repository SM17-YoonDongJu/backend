package com.soma.backend.devtools;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.soma.backend.domain.chat.entity.ChatMessage;
import com.soma.backend.domain.chat.repository.ChatMessageRepository;
import com.soma.backend.domain.report.entity.AccidentType;
import com.soma.backend.domain.report.entity.IssueReviewStatus;
import com.soma.backend.domain.report.entity.Report;
import com.soma.backend.domain.report.entity.ReportReview;
import com.soma.backend.domain.report.entity.ReportReviewIssue;
import com.soma.backend.domain.report.entity.ReportStatus;
import com.soma.backend.domain.report.entity.UserClaim;
import com.soma.backend.domain.report.entity.claim.ClaimDetails;
import com.soma.backend.domain.report.entity.claim.Hospitalization;
import com.soma.backend.domain.report.entity.claim.MedicalIndemnityDetails;
import com.soma.backend.domain.report.repository.ReportRepository;
import com.soma.backend.domain.report.repository.ReportReviewRepository;
import com.soma.backend.domain.report.repository.UserClaimRepository;

/**
 * dev DB에 이미 등록된 사용자를 대상으로 report(user_claims+reports)·report_reviews·채팅 mock 데이터를
 * 1회성으로 채워 넣는 임시 시딩 러너(이슈 없음, 프론트/QA 테스트용 요청). {@code PiiHmacIndexBackfillRunner}
 * (이슈 #232, PR #233/#234) 선례와 동일하게 property로 게이트하고, dev에서 1회 실행한 뒤 후속 커밋에서
 * 이 클래스를 삭제한다 — 코드베이스에 영구히 남기지 않는다.
 *
 * <p>PII 컬럼(user_claims.question/description/additional_information/details, reports.question)은
 * 반드시 엔티티 팩터리(JPA 저장) 경로로만 만든다 — {@code PiiCipher}/컨버터가 자동으로 암호화한다.
 * raw SQL은 (1) 암호화 대상이 아닌 컬럼이면서 (2) Spring에 정식 쓰기 경로가 없거나 그 경로로는 원하는
 * 시딩 상태를 만들 수 없는 경우에만 쓴다 — {@code chatroom}(팩터리는 있으나 백데이트·임의 status가 필요,
 * {@code ChatRoomFixture} 테스트 주석 참고), {@code reports}의 AI 초안 컬럼(claimed_min_amount 등 —
 * 운영에서는 report_worker가 SQL로 직접 쓴다), {@code report_reviews.status=COUNSELING}(정식 경로는 채팅방
 * 개설을 동반하는데 시더는 그 조합을 분리해야 한다), 그리고 시딩 데이터를 과거 시점처럼 보이게 하는
 * created_at/updated_at 백데이트(둘 다 {@code updatable=false}이거나 JPA auditing이 저장 시점을 자동
 * 기록해 엔티티 API로는 과거 값을 넣을 수 없다).
 */
@Component
@Profile("!test")
public class DevMockDataSeedRunner implements ApplicationRunner {

  private static final UUID CUSTOMER_QA = UUID.fromString("685f3c48-5371-4c50-848d-fce2e8e7e4a2");
  private static final UUID CUSTOMER_1 = UUID.fromString("52ac6595-6653-4699-9f3a-6f887740b8a9");
  private static final UUID CUSTOMER_2 = UUID.fromString("e33bbc29-0ff9-4aab-9f1e-5ef878995150");
  private static final UUID CUSTOMER_3 = UUID.fromString("3d04063e-1552-4213-b719-05622a675f71");
  private static final UUID CUSTOMER_4 = UUID.fromString("6f9f6840-8424-4646-8f37-cc42ff6446cc");

  private static final UUID ADJUSTER_QA = UUID.fromString("81a57251-4188-4f2e-b361-f080aa80b588");
  private static final UUID ADJUSTER_1 = UUID.fromString("bfa5897b-495b-4943-82d0-61106d2ae43a");
  private static final UUID ADJUSTER_2 = UUID.fromString("2e022c0d-27d2-4c62-8a68-31b9348af828");

  private final boolean enabled;
  private final UserClaimRepository userClaimRepository;
  private final ReportRepository reportRepository;
  private final ReportReviewRepository reportReviewRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final JdbcTemplate jdbcTemplate;

  private LocalDateTime now;

  public DevMockDataSeedRunner(
      @Value("${app.dev-seed.enabled:false}") boolean enabled,
      UserClaimRepository userClaimRepository,
      ReportRepository reportRepository,
      ReportReviewRepository reportReviewRepository,
      ChatMessageRepository chatMessageRepository,
      JdbcTemplate jdbcTemplate) {
    this.enabled = enabled;
    this.userClaimRepository = userClaimRepository;
    this.reportRepository = reportRepository;
    this.reportReviewRepository = reportReviewRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    this.now = LocalDateTime.now();

    seedNotSelected();
    seedClosedSingleReview();
    seedClosedTrafficConcussion();
    seedClosedWithRejectedSibling();
    seedAwaitingAdoptionSingleReview();
    seedAwaitingAdoptionCompetingReviews();
    seedCounselingFire();
    seedCounselingLiability();
    seedAwaitingInspectionAppendicitis();
    seedAwaitingInspectionTraffic();
  }

  // ---------------------------------------------------------------------
  // 1) NOT_SELECTED — 검수(SENT)까지만 진행되고 상담으로 이어지지 못한 채 스윕으로 미채택 처리된 케이스.
  // ---------------------------------------------------------------------
  private void seedNotSelected() {
    LocalDateTime reportAt = now.minusDays(40);
    ClaimDetails details = ClaimDetails.of(AccidentType.OTHER, List.of("급성 위장염"), List.of());
    UUID claimId = createClaim(CUSTOMER_4, AccidentType.OTHER, details,
        "여행 중에 배탈이 나서 현지 병원에 다녀왔는데 국내 실손보험으로 해외 진료비도 청구가 가능한지 궁금합니다.",
        "해외 여행 중 상한 음식을 먹고 급성 위장염 진단을 받아 현지 병원에서 진료와 약 처방을 받았습니다. "
            + "귀국 후에도 며칠 더 약을 먹었습니다.",
        "영수증과 진료확인서는 전부 영문으로 되어 있는데 번역 공증이 필요한지 궁금합니다.",
        300_000, reportAt.toLocalDate().minusDays(3), reportAt);

    Report report = createReport(CUSTOMER_4, claimId, AccidentType.OTHER,
        "여행 중에 배탈이 나서 현지 병원에 다녀왔는데 국내 실손보험으로 해외 진료비도 청구가 가능한지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 200_000, 350_000, 300_000,
        List.of("해외여행 실손의료비"), List.of(), List.of("약관 제5조 해외 발생 의료비 보상 기준"),
        "해외 발생 상병으로 국내 실손 보상 여부 확인 필요", "medium");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_QA);
    review.updateReviewContent(250_000, 320_000,
        List.of("해외여행 실손의료비"), List.of(),
        List.of("약관 제5조 해외 발생 의료비 보상 기준"),
        "제출하신 영문 영수증과 진료확인서 확인했습니다. 국내 실손 특약상 해외 발생 의료비도 보상 대상이라 "
            + "번역 공증 없이 원본과 번역본만 함께 제출하시면 됩니다. 다만 접수가 지연되고 있어 빠른 확인 부탁드립니다.");
    reportReviewRepository.save(review);
    backdate("report_reviews", review.getId(), reportAt.plusDays(1), reportAt.plusDays(1));

    report.applyReviewStart();
    report.markNotSelected();
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(8));
  }

  // ---------------------------------------------------------------------
  // 2) CLOSED(단일 제안 채택) — 실손 신경차단술 반복 시술 케이스.
  // ---------------------------------------------------------------------
  private void seedClosedSingleReview() {
    LocalDateTime reportAt = now.minusDays(35);
    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("요추 추간판탈출증"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(20), reportAt.toLocalDate().minusDays(16),
            "보존적 치료 및 신경차단술")),
        List.of("신경차단술"), List.of(), "included", null, List.of());
    UUID claimId = createClaim(CUSTOMER_1, AccidentType.MEDICAL_INDEMNITY, details,
        "허리 디스크로 신경차단술을 3번 받았는데 실손 보험금이 회차별로 다 나오는지 궁금합니다.",
        "무거운 짐을 들다가 허리를 삐끗한 후 다리까지 저려서 병원에 갔더니 디스크 진단을 받았습니다. "
            + "수술 없이 신경차단술로 통증을 조절하며 치료 중입니다.",
        "영수증은 회차별로 각각 발급받아서 전부 제출했습니다.",
        900_000, reportAt.toLocalDate().minusDays(20), reportAt);

    Report report = createReport(CUSTOMER_1, claimId, AccidentType.MEDICAL_INDEMNITY,
        "허리 디스크로 신경차단술을 3번 받았는데 실손 보험금이 회차별로 다 나오는지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 800_000, 950_000, 900_000,
        List.of("질병통원 실손의료비", "비급여 주사료 특약"), List.of(), List.of("약관 제8조 통원의료비 지급기준"),
        "비수술적 통증 치료(신경차단술) 반복 시행, 회차별 청구 가능 여부 확인 필요", "high");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_1);
    review.updateReviewContent(800_000, 950_000,
        List.of("질병통원 실손의료비", "비급여 주사료 특약"), List.of(),
        List.of("약관 제8조 통원의료비 지급기준"),
        "제출하신 신경차단술 시술 기록과 영수증을 확인한 결과, 3회 시술 모두 실손의료비 지급 대상으로 확인됩니다. "
            + "안내드린 금액 기준으로 청구 진행하시면 됩니다.");
    review.accept();
    review.upsertIssue(null, new ReportReviewIssue(null, "신경차단술 반복 시행 인정 여부",
        "1년 내 동일 부위 신경차단술 3회 시행, 약관상 통원 1일 1회 한도 내 각 회차 인정",
        900_000, IssueReviewStatus.ACCEPTED, "3회 모두 통원의료비 지급 대상으로 인정", null, null));
    reportReviewRepository.save(review);
    backdate("report_reviews", review.getId(), reportAt.plusDays(2), reportAt.plusDays(5));
    backdateReviewIssues(review, reportAt.plusDays(2));

    report.applyReviewStart();
    report.accept(ADJUSTER_1);
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(5));

    UUID roomId = insertChatRoom(CUSTOMER_1, ADJUSTER_1, report.getId(), review.getId(), "CLOSED",
        reportAt.plusDays(3));
    List<ChatMessageSeed> messages = List.of(
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 시작되었습니다."), reportAt.plusDays(3)),
        new ChatMessageSeed(ADJUSTER_1,
            ChatMessage.text(roomId, ADJUSTER_1, "안녕하세요, 검수 내용 안내드리려고 연락드렸습니다. 신경차단술 3회 모두 실손 지급 대상으로 확인됐어요."),
            reportAt.plusDays(3).plusMinutes(2)),
        new ChatMessageSeed(CUSTOMER_1,
            ChatMessage.text(roomId, CUSTOMER_1, "다행이네요! 혹시 세 번 다 같은 금액으로 나오는 건가요?"),
            reportAt.plusDays(3).plusMinutes(9)),
        new ChatMessageSeed(ADJUSTER_1,
            ChatMessage.text(roomId, ADJUSTER_1, "시술 당일 비급여 항목에 따라 조금씩 차이가 있을 수 있는데, 대략 안내드린 범위 안에서 나올 예정입니다."),
            reportAt.plusDays(3).plusMinutes(12)),
        new ChatMessageSeed(CUSTOMER_1, ChatMessage.text(roomId, CUSTOMER_1, "네 알겠습니다. 제안 수락할게요. 감사합니다!"),
            reportAt.plusDays(3).plusMinutes(20)),
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 종료되었습니다."), reportAt.plusDays(3).plusMinutes(21)));
    saveMessages(messages);
    patchChatRoomLastMessage(roomId, "상담이 종료되었습니다.", reportAt.plusDays(3).plusMinutes(21));
  }

  // ---------------------------------------------------------------------
  // 3) CLOSED(단일 제안 채택) — 교통사고 뇌진탕 케이스.
  // ---------------------------------------------------------------------
  private void seedClosedTrafficConcussion() {
    LocalDateTime reportAt = now.minusDays(30);
    ClaimDetails details = ClaimDetails.of(AccidentType.TRAFFIC, List.of("뇌진탕", "경추 염좌"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(28), reportAt.toLocalDate().minusDays(26),
            "경과관찰 입원")));
    UUID claimId = createClaim(CUSTOMER_3, AccidentType.TRAFFIC, details,
        "교차로에서 사고가 났는데 뇌진탕 진단을 받아서 그런지 어지럼증이 계속 있습니다. 후유증에 대한 보상도 받을 수 있나요?",
        "교차로에서 신호를 받고 진입했는데 신호 위반 차량과 충돌했습니다. 처음엔 괜찮은 줄 알았는데 다음날부터 "
            + "어지럽고 두통이 심해서 병원에 갔더니 뇌진탕 소견을 받았습니다.",
        "3주 정도 통원 치료를 받았고 지금은 많이 호전됐습니다. 사고사실확인원은 경찰서에서 발급받아 첨부했습니다.",
        1_100_000, reportAt.toLocalDate().minusDays(28), reportAt);

    Report report = createReport(CUSTOMER_3, claimId, AccidentType.TRAFFIC,
        "교차로에서 사고가 났는데 뇌진탕 진단을 받아서 그런지 어지럼증이 계속 있습니다. 후유증에 대한 보상도 받을 수 있나요?", reportAt);
    patchAiDraft(report.getId(), 950_000, 1_200_000, 1_100_000,
        List.of("교통상해 실손의료비", "상해통원비 특약"), List.of("자동차상해 특약 - 대인접수 확인 필요"),
        List.of("약관 제6조 상해의 정의 및 보상"),
        "뇌진탕 후 지연성 증상, 통원 장기화 가능성 있어 경과 관찰 권고", "medium");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_2);
    review.updateReviewContent(950_000, 1_200_000,
        List.of("교통상해 실손의료비", "상해통원비 특약"), List.of(),
        List.of("약관 제6조 상해의 정의 및 보상"),
        "사고사실확인원과 진단서, 통원 기록 모두 확인했습니다. 뇌진탕 후 어지럼증은 흔한 후유 증상이라 "
            + "통원 치료가 더 필요하시면 그 부분도 계속 청구 가능합니다. 안내드린 금액으로 우선 진행하겠습니다.");
    review.accept();
    reportReviewRepository.save(review);
    backdate("report_reviews", review.getId(), reportAt.plusDays(1), reportAt.plusDays(4));

    report.applyReviewStart();
    report.accept(ADJUSTER_2);
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(4));

    UUID roomId = insertChatRoom(CUSTOMER_3, ADJUSTER_2, report.getId(), review.getId(), "CLOSED",
        reportAt.plusDays(2));
    List<ChatMessageSeed> messages = List.of(
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 시작되었습니다."), reportAt.plusDays(2)),
        new ChatMessageSeed(ADJUSTER_2,
            ChatMessage.text(roomId, ADJUSTER_2, "안녕하세요. 사고 이후 많이 힘드셨을 텐데 지금은 좀 어떠세요?"),
            reportAt.plusDays(2).plusMinutes(3)),
        new ChatMessageSeed(CUSTOMER_3, ChatMessage.text(roomId, CUSTOMER_3, "어지럼증은 많이 줄었는데 가끔 두통이 있어요."),
            reportAt.plusDays(2).plusMinutes(6)),
        new ChatMessageSeed(ADJUSTER_2,
            ChatMessage.text(roomId, ADJUSTER_2, "혹시 통원 치료가 더 필요하시면 그때도 청구 가능하니 참고해주세요. "
                + "우선 지금까지 서류 기준으로는 안내드린 금액으로 지급 가능합니다."),
            reportAt.plusDays(2).plusMinutes(10)),
        new ChatMessageSeed(CUSTOMER_3, ChatMessage.text(roomId, CUSTOMER_3, "네 감사합니다. 제안 수락할게요."),
            reportAt.plusDays(2).plusMinutes(15)),
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 종료되었습니다."), reportAt.plusDays(2).plusMinutes(16)));
    saveMessages(messages);
    patchChatRoomLastMessage(roomId, "상담이 종료되었습니다.", reportAt.plusDays(2).plusMinutes(16));
  }

  // ---------------------------------------------------------------------
  // 4) CLOSED — 형제 제안(REJECTED) + 채택 제안(ACCEPTED). 상담 단계 없이 바로 채택된 케이스.
  // ---------------------------------------------------------------------
  private void seedClosedWithRejectedSibling() {
    LocalDateTime reportAt = now.minusDays(25);
    ClaimDetails details = ClaimDetails.of(AccidentType.FIRE, List.of("연기 흡입에 의한 기관지염"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(24), reportAt.toLocalDate().minusDays(23),
            "경과관찰 입원")));
    UUID claimId = createClaim(CUSTOMER_2, AccidentType.FIRE, details,
        "상가 화재로 대피하다가 연기를 마셔서 병원에 갔는데, 상가 화재보험이랑 제 개인 실손보험 중 어디서 먼저 "
            + "보상받아야 하는지 궁금합니다.",
        "일하던 상가 건물에 화재가 발생해서 대피 중 연기를 흡입했습니다. 응급실에서 하루 입원 후 퇴원했습니다.",
        "상가 화재 관련 소방서 화재현장조사서는 건물주 측에서 받아서 공유해주기로 했습니다.",
        500_000, reportAt.toLocalDate().minusDays(24), reportAt);

    Report report = createReport(CUSTOMER_2, claimId, AccidentType.FIRE,
        "상가 화재로 대피하다가 연기를 마셔서 병원에 갔는데, 상가 화재보험이랑 제 개인 실손보험 중 어디서 먼저 "
            + "보상받아야 하는지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 400_000, 600_000, 500_000,
        List.of("질병통원 실손의료비"), List.of("화재배상책임 특약 - 건물주 보험과 중복 확인 필요"),
        List.of("약관 제4조 중복보험 처리 기준"),
        "상가 건물 화재보험과의 중복보상 여부 확인 필요", "low");

    ReportReview rejected = new ReportReview(report.getId(), ADJUSTER_QA);
    rejected.updateReviewContent(300_000, 500_000, List.of(), List.of(), List.of(),
        "제출된 진단서 기준으로 검토했으나, 상가 건물 화재보험의 1차 보상 범위를 먼저 확인해야 중복 여부를 "
            + "판단할 수 있어 이번 제안은 보류합니다.");
    rejected.reject();
    reportReviewRepository.save(rejected);
    backdate("report_reviews", rejected.getId(), reportAt.plusDays(1), reportAt.plusDays(3));

    ReportReview accepted = new ReportReview(report.getId(), ADJUSTER_2);
    accepted.updateReviewContent(450_000, 550_000,
        List.of("질병통원 실손의료비"), List.of(),
        List.of("약관 제4조 중복보험 처리 기준"),
        "상가 건물 화재보험과는 보장 항목이 겹치지 않는 개인 실손 특약 대상으로 확인되어, 안내드린 금액으로 "
            + "청구 가능합니다.");
    accepted.accept();
    accepted.upsertIssue(null, new ReportReviewIssue(null, "건물 화재보험과의 중복보상 여부",
        "상가 건물 화재보험은 재산 손해를, 개인 실손은 본인 치료비를 보장해 보장 항목이 겹치지 않음",
        500_000, IssueReviewStatus.MODIFIED, "중복이 아닌 것으로 판단해 전액 인정",
        "최초 검토 시 중복 우려로 보류했으나 보장 항목을 재확인해 인정으로 변경", null));
    reportReviewRepository.save(accepted);
    backdate("report_reviews", accepted.getId(), reportAt.plusDays(2), reportAt.plusDays(4));
    backdateReviewIssues(accepted, reportAt.plusDays(2));

    report.applyReviewStart();
    report.accept(ADJUSTER_2);
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(4));
  }

  // ---------------------------------------------------------------------
  // 5) AWAITING_ADOPTION(단일 제안 SENT) — 갑상선암 진단비 케이스.
  // ---------------------------------------------------------------------
  private void seedAwaitingAdoptionSingleReview() {
    LocalDateTime reportAt = now.minusDays(10);
    ClaimDetails details = ClaimDetails.of(AccidentType.CANCER_DIAGNOSIS, List.of("갑상선 유두암"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(9), reportAt.toLocalDate().minusDays(5),
            "갑상선 전절제술")));
    UUID claimId = createClaim(CUSTOMER_2, AccidentType.CANCER_DIAGNOSIS, details,
        "건강검진에서 우연히 발견된 갑상선암인데, 암 진단비가 가입한 특약대로 전액 나올 수 있는지, 혹시 감액 "
            + "지급되는 경우도 있는지 궁금합니다.",
        "재작년 건강검진에서 갑상선에 결절이 있다고 해서 추적관찰하다가 이번에 조직검사에서 유두암으로 확진됐습니다. "
            + "전절제술을 받았고 방사성요오드 치료도 예정되어 있습니다.",
        "가입 당시 설계사님이 소액암이 아니라 일반암으로 처리된다고 설명해주셨던 게 기억나서 확인 부탁드립니다.",
        20_000_000, reportAt.toLocalDate().minusDays(9), reportAt);

    Report report = createReport(CUSTOMER_2, claimId, AccidentType.CANCER_DIAGNOSIS,
        "건강검진에서 우연히 발견된 갑상선암인데, 암 진단비가 가입한 특약대로 전액 나올 수 있는지, 혹시 감액 "
            + "지급되는 경우도 있는지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 18_000_000, 22_000_000, 20_000_000,
        List.of("암진단비(일반암)", "수술비 특약"), List.of("방사성동위원소 치료비 특약 - 청구 서류 미제출"),
        List.of("약관 제3조 암의 정의 및 진단확정"), "갑상선암 일반암/소액암 분류 기준 확인 필요", "medium");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_QA);
    review.updateReviewContent(18_000_000, 22_000_000,
        List.of("암진단비(일반암)", "수술비 특약"),
        List.of("방사성동위원소 치료비 특약 - 청구 서류 미제출"),
        List.of("약관 제3조 암의 정의 및 진단확정", "대법원 2018다1234 판결(경계성종양 관련)"),
        "제출하신 조직검사 결과지와 수술기록지를 확인했습니다. 갑상선 유두암은 약관상 일반암 분류 기준(침윤 "
            + "정도)에 따라 갈리는 경우가 있어, 병리 소견서의 침윤 범위를 한 번 더 확인 후 최종 의견 드리겠습니다.");
    review.upsertIssue(null, new ReportReviewIssue(null, "갑상선암 일반암/소액암 분류",
        "병리 소견서상 침윤 범위 재확인 필요 — 결과에 따라 진단비 지급률이 달라질 수 있음",
        4_000_000, IssueReviewStatus.MODIFIED, "일반암 가능성이 높으나 서류 보완 후 최종 확정",
        "1차 검토에서는 소액암 우려가 있었으나 침윤 소견 재확인 후 일반암 쪽으로 조정", null));
    reportReviewRepository.save(review);
    backdate("report_reviews", review.getId(), reportAt.plusDays(2), reportAt.plusDays(2));
    backdateReviewIssues(review, reportAt.plusDays(2));

    report.applyReviewStart();
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(2));
  }

  // ---------------------------------------------------------------------
  // 6) AWAITING_ADOPTION(경쟁 제안 2건 SENT) — 무릎 십자인대 재건술 후유장해 케이스.
  // ---------------------------------------------------------------------
  private void seedAwaitingAdoptionCompetingReviews() {
    LocalDateTime reportAt = now.minusDays(8);
    ClaimDetails details = ClaimDetails.of(AccidentType.DISABILITY,
        List.of("우측 무릎 십자인대 파열", "반월상 연골판 손상"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(7), reportAt.toLocalDate().minusDays(3),
            "십자인대 재건술")));
    UUID claimId = createClaim(CUSTOMER_3, AccidentType.DISABILITY, details,
        "운동하다 다쳐서 십자인대 재건술을 받았는데 후유장해 진단까지 나올 정도인지, 장해보험금 청구가 "
            + "가능한지 궁금합니다.",
        "축구 동호회 활동 중 방향 전환을 하다가 무릎이 꺾이면서 다쳤습니다. MRI 검사에서 전방십자인대 완전파열과 "
            + "반월상연골판 손상이 함께 확인되어 재건술을 받았습니다. 재활 중인데 무릎을 완전히 펴기가 아직 힘듭니다.",
        "재활병원 통원 치료 영수증도 계속 모으고 있는데 나중에 한꺼번에 제출해도 되는지 궁금합니다.",
        3_500_000, reportAt.toLocalDate().minusDays(7), reportAt);

    Report report = createReport(CUSTOMER_3, claimId, AccidentType.DISABILITY,
        "운동하다 다쳐서 십자인대 재건술을 받았는데 후유장해 진단까지 나올 정도인지, 장해보험금 청구가 "
            + "가능한지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 2_500_000, 3_200_000, 3_500_000,
        List.of("질병상해 수술비 특약"), List.of("후유장해 특약 - 장해진단서 미제출로 판단 보류"),
        List.of("약관 제9조 후유장해의 정의"), "수술 후 6개월 경과 후 장해평가 필요, 현재는 실손 항목만 확정 가능", "low");

    ReportReview reviewA = new ReportReview(report.getId(), ADJUSTER_QA);
    reviewA.updateReviewContent(2_800_000, 3_200_000,
        List.of("질병상해 수술비 특약"), List.of(), List.of("약관 제9조 후유장해의 정의"),
        "재건술 기록과 MRI 판독 소견을 확인했습니다. 현재 재활 경과를 좀 더 지켜본 뒤 후유장해 평가가 가능한 "
            + "시점(수술 후 6개월)에 장해진단서를 받아보시는 걸 권해드립니다.");
    reportReviewRepository.save(reviewA);
    backdate("report_reviews", reviewA.getId(), reportAt.plusDays(1), reportAt.plusDays(1));

    ReportReview reviewB = new ReportReview(report.getId(), ADJUSTER_1);
    reviewB.updateReviewContent(2_500_000, 3_000_000,
        List.of("질병상해 수술비 특약"), List.of(), List.of(),
        "동일 상병 관련 서류 검토 결과, 우선 실손 의료비 항목부터 청구 진행하시고 장해 관련은 향후 장해진단서 "
            + "발급 이후 별도로 진행하는 것을 제안드립니다.");
    reviewB.upsertIssue(null, new ReportReviewIssue(null, "장해평가 시기 관련 추가 안내",
        "수술 후 최소 6개월 경과 시점에 장해진단서 재발급 권고 — 사정사가 직접 추가한 안내 항목",
        null, IssueReviewStatus.ADDED, "재활 경과에 따라 장해율이 달라질 수 있어 조기 평가는 권하지 않음", null, null));
    reportReviewRepository.save(reviewB);
    backdate("report_reviews", reviewB.getId(), reportAt.plusDays(2), reportAt.plusDays(2));
    backdateReviewIssues(reviewB, reportAt.plusDays(2));

    report.applyReviewStart();
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(2));
  }

  // ---------------------------------------------------------------------
  // 7) COUNSELING — 주방 화재 대피 중 일산화탄소 중독·화상 케이스, 채팅 진행 중(ACTIVE).
  // ---------------------------------------------------------------------
  private void seedCounselingFire() {
    LocalDateTime reportAt = now.minusDays(6);
    ClaimDetails details = ClaimDetails.of(AccidentType.FIRE,
        List.of("일산화탄소 중독", "1도 화상(우측 손)"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(5), reportAt.toLocalDate().minusDays(3),
            "고압산소치료 및 화상 드레싱")));
    UUID claimId = createClaim(CUSTOMER_4, AccidentType.FIRE, details,
        "집에 화재가 나서 대피하다가 다쳤는데, 화재보험이랑 실손보험을 같이 청구할 수 있는지, 순서는 어떻게 "
            + "되는지 궁금합니다.",
        "새벽에 주방에서 화재가 발생해서 대피하다가 손에 화상을 입고 연기를 흡입해서 일산화탄소 중독 소견으로 "
            + "응급 이송됐습니다. 고압산소치료를 받고 퇴원했습니다.",
        "소방서 화재현장조사서는 발급받았고, 집 수리 견적서는 별도로 준비 중입니다.",
        4_200_000, reportAt.toLocalDate().minusDays(5), reportAt);

    Report report = createReport(CUSTOMER_4, claimId, AccidentType.FIRE,
        "집에 화재가 나서 대피하다가 다쳤는데, 화재보험이랑 실손보험을 같이 청구할 수 있는지, 순서는 어떻게 "
            + "되는지 궁금합니다.", reportAt);
    patchAiDraft(report.getId(), 3_800_000, 4_500_000, 4_200_000,
        List.of("질병상해 실손의료비", "화재배상책임 특약"), List.of(),
        List.of("약관 제7조 상해 치료비 지급기준"), "화재보험과 실손 중복 청구 가능 항목 정리 필요", "high");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_2);
    review.updateReviewContent(3_800_000, 4_500_000,
        List.of("질병상해 실손의료비", "화재배상책임 특약"), List.of(),
        List.of("약관 제7조 상해 치료비 지급기준"),
        "화재현장조사서와 진단서 모두 확인했습니다. 실손의료비와 화재 배상 관련 특약이 중복 청구 가능한 "
            + "항목인지 정리해서 안내드리겠습니다.");
    reportReviewRepository.save(review);
    forceReviewCounseling(review.getId());
    backdate("report_reviews", review.getId(), reportAt.plusDays(1), reportAt.plusDays(2));

    report.applyReviewStart();
    report.applyReviewTransition(ReportStatus.COUNSELING);
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(2));

    LocalDateTime chatAt = reportAt.plusDays(2);
    UUID roomId = insertChatRoom(CUSTOMER_4, ADJUSTER_2, report.getId(), review.getId(), "ACTIVE", chatAt);
    List<ChatMessageSeed> messages = List.of(
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 시작되었습니다."), chatAt),
        new ChatMessageSeed(ADJUSTER_2,
            ChatMessage.text(roomId, ADJUSTER_2, "안녕하세요, 화재사고 관련해서 검수 내용 확인했습니다. 우선 많이 놀라셨을 텐데 지금은 괜찮으신가요?"),
            chatAt.plusMinutes(4)),
        new ChatMessageSeed(CUSTOMER_4,
            ChatMessage.text(roomId, CUSTOMER_4, "네 다행히 손 화상은 경미해서 지금은 많이 나았어요. 그런데 화재보험이랑 실손을 같이 받을 수 있는 "
                + "건지가 계속 궁금해서요."),
            chatAt.plusMinutes(9)),
        new ChatMessageSeed(ADJUSTER_2,
            ChatMessage.text(roomId, ADJUSTER_2, "네, 두 보험이 보장하는 항목이 달라서 중복 청구가 가능합니다. 실손은 치료비 실비를, 화재보험은 "
                + "재산 손해와 배상 관련 부분을 보장해요. 제출해주신 서류 기준으로는 문제없이 진행 가능할 것 같습니다."),
            chatAt.plusMinutes(15)),
        new ChatMessageSeed(CUSTOMER_4, ChatMessage.text(roomId, CUSTOMER_4, "그럼 제가 추가로 더 준비해야 할 서류가 있을까요?"),
            chatAt.plusMinutes(19)),
        new ChatMessageSeed(ADJUSTER_2,
            ChatMessage.text(roomId, ADJUSTER_2, "집 수리 견적서만 받으시면 됩니다. 받으시는 대로 사진 올려주시면 바로 검토해드릴게요."),
            chatAt.plusMinutes(23)),
        new ChatMessageSeed(CUSTOMER_4, ChatMessage.text(roomId, CUSTOMER_4, "네 알겠습니다 감사합니다!"),
            chatAt.plusMinutes(25)));
    saveMessages(messages);
    patchChatRoomLastMessage(roomId, "네 알겠습니다 감사합니다!", chatAt.plusMinutes(25));
  }

  // ---------------------------------------------------------------------
  // 8) COUNSELING — 세탁기 호스 파손으로 인한 누수 배상책임 케이스, 채팅 진행 중(ACTIVE).
  // ---------------------------------------------------------------------
  private void seedCounselingLiability() {
    LocalDateTime reportAt = now.minusDays(4);
    ClaimDetails details = ClaimDetails.of(AccidentType.LIABILITY,
        List.of("타인 재물 손괴에 대한 일상생활배상책임"), List.of());
    UUID claimId = createClaim(CUSTOMER_QA, AccidentType.LIABILITY, details,
        "이웃집에 물이 새서 배상 문제가 생겼는데 일상생활배상책임보험으로 처리가 가능한지, 어느 정도 보상되는지 "
            + "알고 싶습니다.",
        "저희 집 세탁기 호스가 파손되면서 아래층으로 누수가 발생했습니다. 아래층 천장과 벽지, 일부 가전제품까지 "
            + "피해를 입으셨다고 합니다. 아래층 분과는 이야기가 잘 되고 있는 상황입니다.",
        "아래층 피해 사진과 수리업체 견적서를 받아뒀습니다. 원만하게 합의하고 싶은데 보험 처리 절차가 궁금합니다.",
        2_000_000, reportAt.toLocalDate().minusDays(3), reportAt);

    Report report = createReport(CUSTOMER_QA, claimId, AccidentType.LIABILITY,
        "이웃집에 물이 새서 배상 문제가 생겼는데 일상생활배상책임보험으로 처리가 가능한지, 어느 정도 보상되는지 "
            + "알고 싶습니다.", reportAt);
    patchAiDraft(report.getId(), 1_500_000, 2_000_000, 2_000_000,
        List.of("일상생활배상책임 특약"), List.of(),
        List.of("약관 제11조 일상생활배상책임의 보상범위"), "고의·중과실 여부 확인 필요, 그 외 지급 요건 충족", "high");

    ReportReview review = new ReportReview(report.getId(), ADJUSTER_QA);
    review.updateReviewContent(1_500_000, 2_000_000,
        List.of("일상생활배상책임 특약"), List.of(),
        List.of("약관 제11조 일상생활배상책임의 보상범위"),
        "누수 원인과 피해 사진, 견적서 모두 확인했습니다. 일상생활배상책임 특약으로 처리 가능한 사안으로 "
            + "보입니다. 다만 고의·중과실이 없었는지 확인이 필요해 몇 가지 여쭤보고 싶습니다.");
    reportReviewRepository.save(review);
    forceReviewCounseling(review.getId());
    backdate("report_reviews", review.getId(), reportAt.plusHours(20), reportAt.plusDays(1));

    report.applyReviewStart();
    report.applyReviewTransition(ReportStatus.COUNSELING);
    reportRepository.save(report);
    backdate("reports", report.getId(), reportAt, reportAt.plusDays(1));

    LocalDateTime chatAt = reportAt.plusDays(1);
    UUID roomId = insertChatRoom(CUSTOMER_QA, ADJUSTER_QA, report.getId(), review.getId(), "ACTIVE", chatAt);
    List<ChatMessageSeed> messages = List.of(
        new ChatMessageSeed(null, ChatMessage.system(roomId, "상담이 시작되었습니다."), chatAt),
        new ChatMessageSeed(ADJUSTER_QA,
            ChatMessage.text(roomId, ADJUSTER_QA, "안녕하세요, 누수 사고 건으로 연락드렸습니다. 세탁기 호스는 노후로 인한 자연 파손이었을까요?"),
            chatAt.plusMinutes(5)),
        new ChatMessageSeed(CUSTOMER_QA,
            ChatMessage.text(roomId, CUSTOMER_QA, "네 맞아요, 오래된 호스라 저희도 모르는 사이에 조금씩 새고 있었던 것 같아요. "
                + "일부러 그런 건 전혀 아닙니다."),
            chatAt.plusMinutes(11)),
        new ChatMessageSeed(ADJUSTER_QA,
            ChatMessage.text(roomId, ADJUSTER_QA, "네, 고의·중과실이 없는 자연 파손이면 보상 대상이 맞습니다. 아래층 견적서 기준으로 "
                + "안내드린 금액 내에서 지급 진행하겠습니다."),
            chatAt.plusMinutes(16)),
        new ChatMessageSeed(CUSTOMER_QA, ChatMessage.text(roomId, CUSTOMER_QA, "감사합니다! 아래층 분께도 바로 안내드릴게요."),
            chatAt.plusMinutes(19)));
    saveMessages(messages);
    patchChatRoomLastMessage(roomId, "감사합니다! 아래층 분께도 바로 안내드릴게요.", chatAt.plusMinutes(19));
  }

  // ---------------------------------------------------------------------
  // 9) AWAITING_INSPECTION — 급성 충수염 수술 케이스(검수 대기, 최근 접수).
  // ---------------------------------------------------------------------
  private void seedAwaitingInspectionAppendicitis() {
    LocalDateTime reportAt = now.minusDays(2);
    ClaimDetails details = new MedicalIndemnityDetails(
        List.of("급성 충수염", "복막염 의증"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(2), reportAt.toLocalDate().minusDays(1),
            "충수절제술 및 경과관찰")),
        List.of("충수절제술"), List.of(), null, reportAt.toLocalDate().minusDays(2).toString(), List.of());
    UUID claimId = createClaim(CUSTOMER_QA, AccidentType.MEDICAL_INDEMNITY, details,
        "복막염 소견까지 나왔는데 실손 보험금이 얼마나 나올 수 있을지 궁금합니다. 수술 후 회복 기간도 길어질 것 "
            + "같아서 걱정이에요.",
        "평소 소화가 잘 안 돼서 넘겼는데 갑자기 오른쪽 아랫배가 심하게 아파서 응급실에 갔더니 급성 충수염 "
            + "진단을 받고 바로 수술했습니다. 수술 중에 복막염 소견도 같이 나왔다고 들었습니다.",
        "회사에 병가를 내고 입원했는데, 실비 청구 서류 중에 진단서랑 수술기록지 외에 추가로 필요한 서류가 있으면 "
            + "안내 부탁드립니다.",
        850_000, reportAt.toLocalDate().minusDays(2), reportAt);

    Report report = createReport(CUSTOMER_QA, claimId, AccidentType.MEDICAL_INDEMNITY,
        "복막염 소견까지 나왔는데 실손 보험금이 얼마나 나올 수 있을지 궁금합니다. 수술 후 회복 기간도 길어질 것 "
            + "같아서 걱정이에요.", reportAt);
    patchAiDraft(report.getId(), null, null, 850_000,
        List.of(), List.of(), List.of(), "AI 초안 미확정 — 검수 대기 중", "low");
    backdate("reports", report.getId(), reportAt, reportAt);
  }

  // ---------------------------------------------------------------------
  // 10) AWAITING_INSPECTION — 후방 추돌사고 손목 골절 케이스(검수 대기, 가장 최근 접수).
  // ---------------------------------------------------------------------
  private void seedAwaitingInspectionTraffic() {
    LocalDateTime reportAt = now.minusDays(1);
    ClaimDetails details = ClaimDetails.of(AccidentType.TRAFFIC,
        List.of("경추 염좌", "요추 염좌", "우측 손목 골절"),
        List.of(new Hospitalization(reportAt.toLocalDate().minusDays(1), reportAt.toLocalDate(), "골절 정복술 및 물리치료")));
    UUID claimId = createClaim(CUSTOMER_1, AccidentType.TRAFFIC, details,
        "신호대기 중 후방 추돌 사고로 손목이 골절됐는데, 상대방 보험사 합의금과 별개로 제 실손·운전자보험에서 "
            + "받을 수 있는 보상이 어느 정도인지 알고 싶습니다.",
        "정차 중에 뒤에서 오는 차량에 추돌당했습니다. 목과 허리가 뻐근해서 병원에 갔더니 염좌 진단을 받았고, "
            + "손목이 아파서 다시 검사해보니 골절이 확인돼서 수술까지 받았습니다.",
        "경찰 조사 결과 100% 상대방 과실로 나왔습니다. 진단서와 사고사실확인원 첨부했습니다.",
        1_200_000, reportAt.toLocalDate().minusDays(1), reportAt);

    Report report = createReport(CUSTOMER_1, claimId, AccidentType.TRAFFIC,
        "신호대기 중 후방 추돌 사고로 손목이 골절됐는데, 상대방 보험사 합의금과 별개로 제 실손·운전자보험에서 "
            + "받을 수 있는 보상이 어느 정도인지 알고 싶습니다.", reportAt);
    patchAiDraft(report.getId(), null, null, 1_200_000,
        List.of(), List.of(), List.of(), "AI 초안 미확정 — 검수 대기 중", "low");
    backdate("reports", report.getId(), reportAt, reportAt);
  }

  // =======================================================================
  // 헬퍼
  // =======================================================================

  private UUID createClaim(UUID userId, AccidentType type, ClaimDetails details, String question,
      String description, String additionalInformation, Integer offeredAmount, LocalDate accidentDate,
      LocalDateTime createdAt) {
    UserClaim claim = userClaimRepository.save(UserClaim.create(
        userId, null, offeredAmount, accidentDate, type, details, question, description, additionalInformation));
    backdate("user_claims", claim.getId(), createdAt, createdAt);
    return claim.getId();
  }

  private Report createReport(UUID userId, UUID claimId, AccidentType type, String question, LocalDateTime createdAt) {
    String caseNo = String.format("%s-%03d", LocalDate.now().toString().replace("-", ""),
        reportRepository.nextCaseNoSequence(LocalDate.now()));
    return reportRepository.save(Report.createPending(userId, null, claimId, type, question, caseNo));
  }

  /**
   * REPORTS의 AI 초안 컬럼을 채운다. 운영에서는 report_worker가 OCR·AI 분석 후 이 컬럼들을 직접 SQL로
   * 갱신하는데(Spring에는 이 컬럼들을 위한 엔티티 setter가 없다), 이 러너로 만드는 리포트는 OCR 트리거를
   * 발행하지 않아 report_worker가 절대 채워주지 않으므로 여기서 직접 채운다. PII 암호화 대상 컬럼이 아니다.
   */
  private void patchAiDraft(UUID reportId, Integer min, Integer max, Integer offered,
      List<String> guarantees, List<String> omitted, List<String> precedents, String treatment,
      String confidenceLevel) {
    jdbcTemplate.update(
        "UPDATE reports SET claimed_min_amount=?, claimed_max_amount=?, offered_amount=?, "
            + "applicable_guarantees=?, omitted_special_contract=?, basis_terms_precedents=?, "
            + "treatment=?, confidence_level=?, is_masked=false WHERE id=?",
        ps -> {
          ps.setObject(1, min);
          ps.setObject(2, max);
          ps.setObject(3, offered);
          ps.setArray(4, ps.getConnection().createArrayOf("text", guarantees.toArray()));
          ps.setArray(5, ps.getConnection().createArrayOf("text", omitted.toArray()));
          ps.setArray(6, ps.getConnection().createArrayOf("text", precedents.toArray()));
          ps.setString(7, treatment);
          ps.setString(8, confidenceLevel);
          ps.setObject(9, reportId);
        });
  }

  /**
   * REPORT_REVIEWS.status를 COUNSELING으로 강제 전이한다. 운영에서는 {@code ReportReview.startCounseling()}
   * (상담 시작 API)이 채팅방 개설과 한 트랜잭션에서 이 상태로 바꾼다. 시더는 방 없이 COUNSELING 행만
   * 만들거나 리포트 전이를 건너뛰는 조합이 필요해 raw SQL을 유지한다. status는 PII 암호화 대상이 아니다.
   */
  private void forceReviewCounseling(UUID reviewId) {
    jdbcTemplate.update("UPDATE report_reviews SET status = 'COUNSELING' WHERE id = ?", reviewId);
  }

  /**
   * chatroom 엔티티 팩터리({@code ChatRoom.openConsultation})는 있지만, 시더는 방을 과거 시점으로 백데이트하고
   * CLOSED 등 임의 status로도 만들어야 해 raw INSERT를 유지한다(created_at은 JPA auditing이 저장 시점으로
   * 덮어쓴다). 대상 컬럼 중 암호화된 것이 없어 raw INSERT로도 안전하다.
   */
  private UUID insertChatRoom(UUID userId, UUID adjusterId, UUID reportId, UUID reportReviewId, String status,
      LocalDateTime createdAt) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO chatroom (id, user_id, adjuster_id, report_id, report_review_id, status, "
            + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        id, userId, adjusterId, reportId, reportReviewId, status, createdAt, createdAt);
    return id;
  }

  private void patchChatRoomLastMessage(UUID roomId, String preview, LocalDateTime at) {
    jdbcTemplate.update(
        "UPDATE chatroom SET last_message=?, last_message_at=?, user_last_read_at=?, "
            + "adjuster_last_read_at=?, updated_at=? WHERE id=?",
        preview, at, at, at, at, roomId);
  }

  private void saveMessages(List<ChatMessageSeed> seeds) {
    for (ChatMessageSeed seed : seeds) {
      ChatMessage saved = chatMessageRepository.save(seed.message());
      backdate("chatroom_messages", saved.getId(), seed.createdAt(), null);
    }
  }

  /** created_at(그리고 있으면 updated_at)을 과거 시점으로 되돌린다 — 둘 다 JPA에서는 auditing이 저장 시점에 자동 기록한다. */
  private void backdate(String table, UUID id, LocalDateTime createdAt, LocalDateTime updatedAt) {
    if (updatedAt != null) {
      jdbcTemplate.update("UPDATE " + table + " SET created_at=?, updated_at=? WHERE id=?", createdAt, updatedAt, id);
    } else {
      jdbcTemplate.update("UPDATE " + table + " SET created_at=? WHERE id=?", createdAt, id);
    }
  }

  /** review 저장(cascade) 시 함께 생성된 쟁점(report_issues_reviews)도 부모 검수 시점에 맞춰 백데이트한다. */
  private void backdateReviewIssues(ReportReview review, LocalDateTime at) {
    for (ReportReviewIssue issue : review.getIssues()) {
      backdate("report_issues_reviews", issue.getId(), at, null);
    }
  }

  private record ChatMessageSeed(UUID senderId, ChatMessage message, LocalDateTime createdAt) {
  }
}
