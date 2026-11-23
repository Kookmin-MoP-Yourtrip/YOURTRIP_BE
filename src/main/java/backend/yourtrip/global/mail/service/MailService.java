package backend.yourtrip.global.mail.service;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MailErrorCode;
import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.MailMetrics.AttemptOutcome;
import backend.yourtrip.global.mail.MailMetrics.MailOutcome;
import backend.yourtrip.global.mail.entity.MailLog;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 인증 메일 발송.
 *
 * <p><b>트랜잭션을 걸지 않는다</b> (이슈 #221). 이 클래스의 DB 작업은 시도마다의 MailLog {@code save()}뿐이고,
 * {@code save()}는 그 자체로 짧은 트랜잭션이라 바깥 트랜잭션 없이도 저장된다. 바깥에 트랜잭션을 걸면
 * <ul>
 *   <li>SMTP를 기다리는 내내 DB 커넥션을 쥔다 — 개선 전 측정에서 이 점유가 커넥션 10개를 바닥내고
 *       메일과 무관한 API까지 멈췄다(docs/tasks/mail-async/measurement.md)</li>
 *   <li>최종 실패의 예외가 롤백을 일으켜, 앞서 저장한 시도별 실패 로그까지 지운다</li>
 * </ul>
 * 메일 발송은 롤백할 수 없으므로 트랜잭션이 보호하는 것도 없다. 다시 붙이지 말 것.
 */
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final MailLogRepository mailLogRepository;
    private final MailMetrics mailMetrics;

    private static final int MAX_RETRY = 3;

    // MailLog.errorMessage 컬럼 길이. SMTP 예외 메시지는 원인 예외를 이어 붙여 이보다 길어지기 쉽다
    // ("Mail server connection failed. Failed messages: ... nested exception is: ...").
    private static final int ERROR_MESSAGE_MAX = 255;

    public void sendVerificationMail(String to, String code) {
        String subject = "[너의 여행은] 이메일 인증번호 안내";
        String body = """
                안녕하세요!
                '너의 여행은' 회원가입 인증번호를 안내드립니다.

                인증번호: %s

                본 메일은 5분간 유효합니다.
                감사합니다 :)
                """.formatted(code);

        sendMailWithLogging(to, subject, body, 0);
    }

    private void sendMailWithLogging(String to, String subject, String text, int attempt) {
        MailLog.MailLogBuilder logBuilder = MailLog.builder()
            .recipient(to)
            .subject(subject)
            .retryCount(attempt);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            message.setFrom("너의 여행은 <th2194@gmail.com>");
            sendTimed(message);

            logBuilder.status("SUCCESS");
            mailMetrics.mail(MailOutcome.SENT);
            System.out.println("[메일 전송 성공] " + to);

        } catch (MailSendException e) {
            logBuilder.status("FAILED").errorMessage(truncate(e.getMessage()));
            System.err.println("[메일 전송 실패] " + to + " → " + e.getMessage());

            if (attempt < MAX_RETRY) {
                System.out.println("[재시도 진행] " + (attempt + 1) + "회차");
                sendMailWithLogging(to, subject, text, attempt + 1);
            } else {
                mailMetrics.mail(MailOutcome.GAVE_UP);
                throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);
            }

        } catch (Exception e) {
            logBuilder.status("FAILED").errorMessage(truncate(e.getMessage()));
            mailMetrics.mail(MailOutcome.GAVE_UP);
            throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);

        } finally {
            mailLogRepository.save(logBuilder.build());
        }
    }

    // 자르지 않으면 finally의 save()가 DataIntegrityViolationException으로 실패해, 그 예외가 원래의 발송 실패를
    // 덮어쓴다(트랜잭션 유무와 무관하다 — IDENTITY 전략이라 save()가 즉시 INSERT한다).
    private static String truncate(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX);
    }

    // SMTP 시도 한 번의 구간만 잰다 — 메시지 조립과 MailLog 저장은 넣지 않는다.
    // 예외 종류와 무관하게 던져졌으면 실패로 센다(재시도 여부 판단은 호출부의 몫이다).
    private void sendTimed(SimpleMailMessage message) {
        long start = System.nanoTime();
        try {
            mailSender.send(message);
            mailMetrics.attempt(AttemptOutcome.SUCCESS, System.nanoTime() - start);
        } catch (RuntimeException e) {
            mailMetrics.attempt(AttemptOutcome.FAILURE, System.nanoTime() - start);
            throw e;
        }
    }

    // ===============================
    //  비밀번호 찾기 메일 전송 메서드
    // ===============================
    public void sendPasswordResetMail(String to, String code) {
        String subject = "[너의 여행은] 비밀번호 재설정 인증번호 안내";
        String body = """
                안녕하세요!
                '너의 여행은' 비밀번호 재설정 인증번호를 안내드립니다.

                인증번호: %s

                본 인증번호는 5분간 유효합니다.
                요청하지 않은 경우 메일을 무시해주세요.
                """.formatted(code);

        sendMailWithLogging(to, subject, body, 0);
    }
}
