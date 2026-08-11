package backend.yourtrip.global.common;

import java.time.LocalDate;

/**
 * S3 오브젝트 key의 prefix 규약을 한곳에 모은 클래스.
 *
 * <p>원래 이 규약은 {@code S3Service.upload()}와 {@code S3Service.copy()} 두 곳에서 각각
 * 조립됐다(둘 다 {@code prefix + LocalDate.now() + "/" + UUID + ext}). 한쪽만 고치면 업로드
 * 경로와 복사(fork/업로드 사본) 경로가 조용히 어긋나는 구조라, 날짜/코스 세그먼트를 결정하는
 * 부분만 여기로 뽑아 두 조립문이 {@code prefix + UUID + ext}로 동일해지게 했다.
 *
 * <p><b>비공개 key가 코스 단위인 이유</b>: mycourse 상세조회는 이미지 한 장마다 CloudFront
 * Signed URL을 개별 발급했는데, 이 서명 연산이 CPU 병목이었다. 정책의 {@code Resource}를
 * {@code private/{courseId}/*} 와일드카드로 잡으면 <b>코스당 한 번만 서명</b>하고 그 결과
 * 쿼리스트링을 그 코스의 모든 이미지 URL에 재사용할 수 있다. 그러려면 key가 코스별로 묶여
 * 있어야 한다 — 기존 형식({@code private/{yyyy-MM-dd}/...})은 소유자·코스 정보가 없어
 * 와일드카드를 코스 단위로 좁힐 수 없었다.
 * (docs/tasks/connection-pool-bottleneck/stage1/design-and-poc.md 참고)
 *
 * <p>공개 key는 서명 대상이 아니라 이 제약과 무관하므로 날짜 기반을 그대로 유지한다.
 */
public final class MediaKeys {

    // CloudFront 배포의 default cache behavior(무서명)가 서빙하는 공개 콘텐츠
    // — uploadcourse 썸네일/장소이미지, feed 미디어, 프로필 이미지 등.
    private static final String PUBLIC_PREFIX = "uploads/";

    // CloudFront 배포의 "private/*" ordered cache behavior(트러스트 키 그룹 서명 필수)가
    // 서빙하는 비공개 콘텐츠 — mycourse 장소 이미지 전용.
    private static final String PRIVATE_PREFIX = "private/";

    private MediaKeys() {
    }

    /**
     * {@code uploads/{yyyy-MM-dd}/} — 날짜별로 묶어 운영 시 오브젝트를 훑기 쉽게 한다.
     */
    public static String publicPrefix() {
        return PUBLIC_PREFIX + LocalDate.now() + "/";
    }

    /**
     * {@code private/{courseId}/} — 서명 정책의 와일드카드 스코프와 정확히 일치시킨다.
     *
     * <p>courseId를 검증하는 이유: null이나 비정상 값이 그대로 들어가면 {@code private/null/...}
     * 같은 key가 조용히 저장되고, 그 이미지는 어떤 코스 스코프 서명으로도 접근할 수 없어
     * 영구적으로 403이 된다. 저장 시점에 터뜨리는 편이 낫다. 호출부(mycourse 업로드/fork)가
     * 이미 코스 존재·소유권을 검증한 뒤라 여기 도달하는 값이 잘못됐다면 사용자 입력 오류가
     * 아니라 내부 불변식 위반이므로 BusinessException이 아닌 IllegalArgumentException을 던진다.
     */
    public static String privatePrefix(Long courseId) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException(
                "비공개 S3 key는 유효한 courseId가 필요하다: " + courseId);
        }
        return PRIVATE_PREFIX + courseId + "/";
    }
}
