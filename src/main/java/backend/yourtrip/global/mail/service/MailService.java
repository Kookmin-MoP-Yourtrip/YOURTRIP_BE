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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;
    private final MailLogRepository mailLogRepository;
    private final MailMetrics mailMetrics;

    private static final int MAX_RETRY = 3;

    @Transactional
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
            logBuilder.status("FAILED").errorMessage(e.getMessage());
            System.err.println("[메일 전송 실패] " + to + " → " + e.getMessage());

            if (attempt < MAX_RETRY) {
                System.out.println("[재시도 진행] " + (attempt + 1) + "회차");
                sendMailWithLogging(to, subject, text, attempt + 1);
            } else {
                mailMetrics.mail(MailOutcome.GAVE_UP);
                throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);
            }

        } catch (Exception e) {
            logBuilder.status("FAILED").errorMessage(e.getMessage());
            mailMetrics.mail(MailOutcome.GAVE_UP);
            throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);

        } finally {
            mailLogRepository.save(logBuilder.build());
        }
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
    @Transactional
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
