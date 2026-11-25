package backend.yourtrip.global.mail;

import java.util.Optional;

/**
 * 메일 재시도 직전에 "이 메일의 인증번호가 아직 유효한가"를 묻는 창구 (이슈 #221, 설계 README 3-4절).
 *
 * <p>재시도 백오프를 기다리는 동안 사용자가 재발송하면 저장된 코드가 새 값으로 바뀐다. 그때 옛 코드의 재시도가
 * 나중에 성공하면, 늦게 도착한 옛 메일의 번호는 검증에서 틀린 번호가 된다. 그래서 재시도마다 현재 코드를 확인한다.
 *
 * <p><b>왜 {@code EmailVerificationStore}를 직접 쓰지 않는가.</b> 메일({@code global})이 회원 도메인
 * ({@code domain.user})을 참조하면 의존 방향이 거꾸로 선다. 이 인터페이스를 {@code global}에 두고 저장소가 상속한다.
 */
public interface VerificationCodeSource {

    /** 지금 유효한 인증번호. 없거나 만료됐으면 빈 Optional. 저장소 장애면 예외를 던질 수 있다. */
    Optional<String> currentCode(String email);
}
