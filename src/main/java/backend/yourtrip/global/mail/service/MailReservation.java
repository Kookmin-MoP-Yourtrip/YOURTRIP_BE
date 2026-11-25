package backend.yourtrip.global.mail.service;

/**
 * 메일 전용 풀에 미리 잡아 둔 자리 하나 (이슈 #221, PR #224 리뷰).
 *
 * <p><b>왜 자리부터 잡는가.</b> 인증번호 발송은 "번호 저장 → 메일 제출" 순서다. 풀이 가득 찬 상태에서 재발송하면
 * 새 번호는 저장됐는데 제출만 503으로 거절된다. 그러면 큐에서 기다리던 이전 메일은 "번호가 바뀌었다"며 버려져
 * 사용자가 유효한 메일을 하나도 받지 못한다. 자리를 먼저 잡고, 잡히지 않으면 번호를 건드리지 않고 503을 낸다.
 *
 * <pre>
 * try (MailReservation mail = mailService.reserve()) {   // 가득 차면 여기서 503 — 번호는 그대로
 *     store.saveCode(email, code);                         // 저장 실패 시 close()가 자리를 돌려준다
 *     mail.sendVerificationMail(email, code);              // 자리가 보장돼 거절되지 않는다
 * }
 * </pre>
 *
 * <p>예약 하나로 메일 한 통만 보낸다. 보낸 뒤의 자리는 발송 작업이 끝날 때 반납되고, 보내지 않고 {@link #close()}하면
 * 그 자리에서 반납된다. 두 번 닫아도 안전하다.
 */
public interface MailReservation extends AutoCloseable {

    void sendVerificationMail(String to, String code);

    void sendPasswordResetMail(String to, String code);

    /** 보내지 않았다면 자리를 돌려준다. 이미 보냈다면 아무것도 하지 않는다. */
    @Override
    void close();
}
