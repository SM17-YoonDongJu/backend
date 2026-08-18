-- 제안(report_reviews) 1건당 상담 채팅방 1개를 DB 레벨에서 보장한다.
-- 애플리케이션 선조회(ChatRoomCommandService.openConsultationRoom)와 커밋 사이의 경쟁 창에서
-- 중복 클릭이 방을 두 개 만드는 것을 막는 최종 방어선이다(GlobalExceptionHandler가 409로 변환).
--
-- 스키마: chatroom은 V40(public -> core 이관) 이후 모든 환경에서 core에 있고, app_owner는 core에
-- CREATE 권한을 가지므로 인덱스 생성이 통과한다(CLAUDE.md Key Configuration 권한 매트릭스).
--
-- 부분 인덱스인 이유: report_review_id는 nullable이다(사정사 검색 경로로 열린 방은 제안이 없다).
-- PostgreSQL은 NULL을 서로 다른 값으로 보므로 전체 UNIQUE로도 동작하지만, 제약 대상이 "제안이 붙은
-- 파이프라인 방"뿐이라는 의도를 인덱스 정의에 남긴다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_chatroom_report_review_id
  ON core.chatroom (report_review_id)
  WHERE report_review_id IS NOT NULL;
