package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.global.ai.candidate.CandidateSourceType;

/**
 * Curator가 고른 후보 하나 (ROADMAP 6-4). <b>계약만 5단계가 정의하고 채우는 것은 6단계다.</b>
 *
 * <p><b>좌표·id·URL 필드가 없는 것이 이 record의 핵심이다.</b> "SEEDED·LISTED는 재검증을
 * 생략한다"의 전제는 목록 항목의 좌표·주소·카테고리를 <b>코드가</b> 승계하는 것이지 LLM이 옮겨
 * 적는 것이 아니다. 스키마에 그 필드를 두는 순간 모델이 값을 지어낼 자리가 생기고, 그 값은
 * 아무도 검증하지 않는다.
 *
 * @param listIndex 목록에서 고른 위치. {@code SUGGESTED}면 null이다. <b>상호명이 아니라 인덱스로
 *                  참조하게 하는 이유</b>는 선별 과제라도 LLM 출력인 이상 "목록에서 골랐다"고
 *                  주장하며 목록에 없는 이름을 내놓을 수 있기 때문이다(6-7의 위조 방어)
 * @param placeName 모델이 적은 상호명. 목록 항목과 대조하는 데 쓰이고, {@code SUGGESTED}일 때만
 *                  실제 검색어가 된다
 */
public record CuratedPlace(CandidateSourceType source, Integer listIndex, String placeName) {
}
