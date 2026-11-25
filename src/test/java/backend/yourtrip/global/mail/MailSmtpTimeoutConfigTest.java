package backend.yourtrip.global.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * SMTP 타임아웃 세 개가 실제 {@link JavaMailSender}까지 전달되는지 잠근다 (이슈 #221).
 *
 * <p>{@code spring.mail.properties}는 문자열 맵이라 키에 오타가 나도 <b>기동 오류 없이 무시된다.</b>
 * 그러면 JavaMail은 기본값인 무한 대기로 돌아가고, 개선 전 측정에서 본 장애(무응답 SMTP 하나에
 * 분당 12통으로 약 50초 만에 전체 API 정지)가 소리 없이 되살아난다. 그래서 값 자체를 단언한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SMTP 타임아웃 설정 (#221)")
class MailSmtpTimeoutConfigTest {

    @Autowired
    private JavaMailSender mailSender;

    @Test
    @DisplayName("연결 5초, 응답 읽기 10초, 쓰기 10초가 JavaMail 세션 속성에 들어간다")
    void timeoutsReachJavaMailSession() {
        assertThat(mailSender).isInstanceOf(JavaMailSenderImpl.class);

        assertThat(((JavaMailSenderImpl) mailSender).getJavaMailProperties())
            .containsEntry("mail.smtp.connectiontimeout", "5000")
            .containsEntry("mail.smtp.timeout", "10000")
            .containsEntry("mail.smtp.writetimeout", "10000");
    }
}
