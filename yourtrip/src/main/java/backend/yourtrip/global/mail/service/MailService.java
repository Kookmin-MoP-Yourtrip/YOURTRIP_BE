package backend.yourtrip.global.mail.service;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MailErrorCode;
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
            mailSender.send(message);

            logBuilder.status("SUCCESS");
            System.out.println("[메일 전송 성공] " + to);

        } catch (MailSendException e) {
            logBuilder.status("FAILED").errorMessage(e.getMessage());
            System.err.println("[메일 전송 실패] " + to + " → " + e.getMessage());

            // 재시도 조건
            if (attempt < MAX_RETRY) {
                System.out.println("[재시도 진행] " + (attempt + 1) + "회차");
                sendMailWithLogging(to, subject, text, attempt + 1);
            } else {
                throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);
            }

        } catch (Exception e) {
            logBuilder.status("FAILED").errorMessage(e.getMessage());
            throw new BusinessException(MailErrorCode.MAIL_SEND_FAILED);

        } finally {
            mailLogRepository.save(logBuilder.build());
        }
    }
}
