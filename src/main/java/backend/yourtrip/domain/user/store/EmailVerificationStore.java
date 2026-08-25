package backend.yourtrip.domain.user.store;

import java.util.Optional;

/**
 * 이메일 인증 흐름(회원가입·비밀번호 찾기)의 상태 저장소.
 * <p>
 * 인증코드·인증완료 여부·임시 비밀번호는 여기 저장된 것이 유일한 원본이다 — DB에 사본이 없다.
 * 만료는 저장소의 TTL이 책임지므로, 호출부는 만료시각을 직접 관리하지 않는다:
 * 만료된 항목은 조회 시 빈 Optional / false로 나타난다.
 */
public interface EmailVerificationStore {

    /** 인증코드를 저장한다. 같은 이메일로 재발급하면 기존 코드를 덮어쓴다. TTL 5분. */
    void saveCode(String email, String code);

    /** 유효한(만료되지 않은) 인증코드를 조회한다. 없거나 만료됐으면 빈 Optional. */
    Optional<String> findCode(String email);

    /** 이메일을 인증 완료 상태로 표시한다. TTL 30분 — 이 시간 안에 가입/재설정을 마쳐야 한다. */
    void markVerified(String email);

    /** 인증 완료 상태인지 확인한다. 만료됐으면 false. */
    boolean isVerified(String email);

    /** 인코딩된 임시 비밀번호를 저장한다. TTL 30분. */
    void saveTempPassword(String email, String encodedPassword);

    /** 유효한 임시 비밀번호를 조회한다. 없거나 만료됐으면 빈 Optional. */
    Optional<String> findTempPassword(String email);

    /** 가입 완료·비밀번호 재설정 완료 시 해당 이메일의 인증 상태를 일괄 삭제한다. */
    void clear(String email);
}
