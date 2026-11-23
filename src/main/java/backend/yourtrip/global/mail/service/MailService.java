package backend.yourtrip.global.mail.service;

import backend.yourtrip.global.exception.RetryLaterException;
import backend.yourtrip.global.exception.errorCode.MailErrorCode;
import backend.yourtrip.global.mail.MailMetrics;
import backend.yourtrip.global.mail.MailMetrics.AttemptOutcome;
import backend.yourtrip.global.mail.MailMetrics.MailOutcome;
import backend.yourtrip.global.mail.MailRetryBackoff;
import backend.yourtrip.global.mail.VerificationCodeSource;
import backend.yourtrip.global.mail.entity.MailLog;
import backend.yourtrip.global.mail.repository.MailLogRepository;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
 *
 * <p><b>발송은 {@code mailExecutor}에서 한다</b> (이슈 #221, 설계 README 3-1절). 호출자는 {@link #reserve()}로 풀의
 * 자리를 잡고, 그 예약으로 제출만 하고 바로 돌아오므로 Tomcat 워커는 SMTP를 기다리지 않는다. 그래서 성공 응답의 의미는 "발송 완료"가 아니라
 * <b>"발송 접수"</b>다. 발송 실패는 응답으로 돌아가지 않고 {@code mail.send}·{@code mail.send.attempt} 지표와
 * MailLog로만 보인다.
 * <ul>
 *   <li>풀이 가득 차면 <b>예약 단계에서</b> 거절하고, 호출한 워커에서 바로 503({@link MailErrorCode#MAIL_BUSY}) +
 *       {@code Retry-After}를 던진다. 제출이 아니라 예약에서 거절하는 이유는 {@link MailReservation} 참고 — 호출자가
 *       번호를 저장하기 전에 거절해야 이미 접수된 이전 메일이 버려지지 않는다. 구조는 {@code AiCourseAdmission}과 같다</li>
 *   <li>{@code @Async}를 쓰지 않는 이유: 거절 처리가 프록시 뒤에 숨어 코드에 드러나지 않고, 같은 클래스 안 호출에는
 *       적용되지 않으며, 단위 테스트에서 동기 실행으로 바꾸기 번거롭다. 생성자로 받은 {@link Executor}에 직접 제출한다</li>
 * </ul>
 *
 * <p><b>재시도는 메일 스레드 안의 반복문이다</b> (설계 README 3-4절). 한 통이 스레드 하나를 쥐고 끝까지 간다.
 * <pre>
 * 시도 → 실패 → 재시도할 실패인가? (인증 오류·5xx 영구 실패면 즉시 포기)
 *      → 백오프 대기(1·2·4초) → 저장된 인증번호가 아직 이 메일의 번호인가? (재발송·만료면 폐기)
 *      → 다음 시도 … → 상한에 닿으면 포기
 * 번호 확인은 첫 시도 전에도 한다 — 큐에서 기다리는 동안 만료될 수 있다.
 * </pre>
 *
 * <p>MailLog는 시도마다 한 행이다. {@code retryCount}는 이름과 달리 <b>몇 번째 시도인가(0부터)</b>이고, 그 의미는
 * 개선 전과 같다. {@code status}: {@code SUCCESS} · {@code FAILED}(실패, 다음 시도 있음) · {@code GAVE_UP}(실패, 마지막) ·
 * {@code STALE}(재시도 직전 폐기 — 이 행은 SMTP를 시도하지 않았다).
 */
@Slf4j
@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final MailLogRepository mailLogRepository;
    private final MailMetrics mailMetrics;
    private final Executor mailExecutor;
    private final VerificationCodeSource codeSource;
    private final MailRetryBackoff backoff;
    // 풀에 들어와 있는 메일(실행 중 + 큐) 수의 상한 = 큐 용량. 예약을 얻은 제출이 풀에서 거절되지 않게 하는 값이다.
    private final Semaphore admission;

    public MailService(JavaMailSender mailSender, MailLogRepository mailLogRepository, MailMetrics mailMetrics,
        @Qualifier("mailExecutor") Executor mailExecutor, VerificationCodeSource codeSource,
        MailRetryBackoff backoff) {
        this.mailSender = mailSender;
        this.mailLogRepository = mailLogRepository;
        this.mailMetrics = mailMetrics;
        this.mailExecutor = mailExecutor;
        this.codeSource = codeSource;
        this.backoff = backoff;
        this.admission = new Semaphore(capacityOf(mailExecutor));
    }

    // 자리 수를 풀 설정에서 읽는다 — 같은 숫자를 두 곳에 두면 한쪽만 바뀌는 일이 생긴다.
    //
    // "스레드 + 큐"가 아니라 "큐"인 이유(PR #224 리뷰): 작업은 본문 끝에서 자리를 반납하는데, 그 순간 워커는 아직 큐에서
    // 다음 작업을 꺼내지 않아 큐가 가득 찬 그대로다. 자리 수가 스레드 + 큐면 이 틈에 성공한 예약의 제출이 큐 포화로
    // 거절되고, 호출자는 이미 인증번호를 덮어쓴 뒤다. 자리 수가 큐 용량이면 큐에 든 메일은 모두 자리를 쥔 메일이라
    // 큐 용량을 넘을 수 없다 — 대가로 풀이 한 번에 받는 메일이 큐 용량(50)으로 줄어든다(스레드 4개는 이 안에서 돈다).
    // 동기 실행기(테스트의 Runnable::run 등)는 넘칠 일이 없어 상한을 두지 않는다.
    static int capacityOf(Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor pool) {
            return pool.getQueueCapacity();
        }
        return Integer.MAX_VALUE;
    }

    // MailLog.errorMessage 컬럼 길이. SMTP 예외 메시지는 원인 예외를 이어 붙여 이보다 길어지기 쉽다
    // ("Mail server connection failed. Failed messages: ... nested exception is: ...").
    private static final int ERROR_MESSAGE_MAX = 255;

    // 풀이 가득 찼을 때 클라이언트에게 주는 재시도 간격의 힌트. 큐 50칸이 정상 속도(초당 4통)로 비는 데 약 12초,
    // SMTP가 느린 날에는 그보다 길다 — 너무 짧으면 거절된 사용자들이 한꺼번에 다시 몰린다.
    static final int RETRY_AFTER_SECONDS = 30;

    /**
     * 메일 풀에 자리 하나를 잡는다. 가득 찼으면 바로 503({@link MailErrorCode#MAIL_BUSY})을 던진다.
     * 호출자는 이 예약을 얻은 <b>뒤에</b> 인증번호를 저장하고 발송한다 — 사용법은 {@link MailReservation}.
     */
    public MailReservation reserve() {
        if (!admission.tryAcquire()) {
            mailMetrics.mail(MailOutcome.REJECTED);
            throw new RetryLaterException(MailErrorCode.MAIL_BUSY, RETRY_AFTER_SECONDS);
        }
        return new Reservation();
    }

    // 이미 번호를 저장한 뒤라 자리 순서가 상관없는 호출(같은 패키지의 테스트)을 위한 단축 경로.
    void sendVerificationMail(String to, String code) {
        try (MailReservation reservation = reserve()) {
            reservation.sendVerificationMail(to, code);
        }
    }

    private final class Reservation implements MailReservation {

        // 요청 스레드 하나만 쓴다 — 동기화할 필요가 없다.
        private boolean consumed;

        @Override
        public void sendVerificationMail(String to, String code) {
            submit(verificationMail(to, code));
        }

        @Override
        public void sendPasswordResetMail(String to, String code) {
            submit(passwordResetMail(to, code));
        }

        // 자리는 발송 작업이 끝날 때 반납한다. 자리 수 = 큐 용량이라 풀이 거절하는 경우는 종료 중뿐이다.
        private void submit(Mail mail) {
            if (consumed) {
                throw new IllegalStateException("예약 하나로 메일 한 통만 보낸다");
            }
            consumed = true;
            try {
                mailExecutor.execute(() -> {
                    try {
                        deliver(mail);
                    } finally {
                        admission.release();
                    }
                });
            } catch (TaskRejectedException e) {
                admission.release();
                mailMetrics.mail(MailOutcome.REJECTED);
                throw new RetryLaterException(MailErrorCode.MAIL_BUSY, RETRY_AFTER_SECONDS);
            }
        }

        @Override
        public void close() {
            if (!consumed) {
                consumed = true;
                admission.release();
            }
        }
    }

    private static Mail verificationMail(String to, String code) {
        String subject = "[너의 여행은] 이메일 인증번호 안내";
        String body = """
                안녕하세요!
                '너의 여행은' 회원가입 인증번호를 안내드립니다.

                인증번호: %s

                본 메일은 5분간 유효합니다.
                감사합니다 :)
                """.formatted(code);

        return new Mail(to, subject, body, code);
    }

    // ===============================
    //  비밀번호 찾기 메일
    // ===============================
    private static Mail passwordResetMail(String to, String code) {
        String subject = "[너의 여행은] 비밀번호 재설정 인증번호 안내";
        String body = """
                안녕하세요!
                '너의 여행은' 비밀번호 재설정 인증번호를 안내드립니다.

                인증번호: %s

                본 인증번호는 5분간 유효합니다.
                요청하지 않은 경우 메일을 무시해주세요.
                """.formatted(code);

        return new Mail(to, subject, body, code);
    }

    private record Mail(String to, String subject, String body, String code) {
    }

    // 메일 스레드에서 돈다. 여기서 던진 예외는 받을 사람이 없으므로 로그로 남기고 끝낸다. 수신자 주소는 로그에 쓰지 않는다.
    private void deliver(Mail mail) {
        try {
            MailOutcome outcome = sendWithRetry(mail);
            mailMetrics.mail(outcome);
            if (outcome == MailOutcome.GAVE_UP) {
                log.warn("인증 메일 발송을 포기했다 — mail.send{outcome=gave_up}·MailLog 참고");
            }
        } catch (RuntimeException e) {
            // 발송 경로가 예상하지 못한 예외(버그). 접수 응답은 이미 나갔으므로 지표가 유일한 흔적이다 —
            // 받은 메일은 반드시 결말 하나로 센다. MailLog 저장 실패는 save()가 삼키므로 여기 오지 않는다.
            mailMetrics.mail(MailOutcome.ERROR);
            log.error("인증 메일 발송 작업이 예외로 끝났다", e);
        }
    }

    private MailOutcome sendWithRetry(Mail mail) {
        for (int attempt = 0; ; attempt++) {
            if (attempt > 0) {
                try {
                    backoff.beforeRetry(attempt);
                } catch (InterruptedException e) {
                    // 종료 대기 시간을 넘겨 풀이 끊었다. 이 메일은 유실된다(설계 README 2-2절 ⓐ의 한계).
                    Thread.currentThread().interrupt();
                    return MailOutcome.GAVE_UP;
                }
            }
            // 첫 시도 전에도 확인한다. 메일은 큐에서 기다렸다가 첫 시도를 하는데, SMTP가 응답하지 않으면 한 통이
            // 스레드를 약 47초(타임아웃 10초 × 4 + 백오프 7초) 쥐어 큐 뒤쪽의 대기가 5분 만료를 넘을 수 있다.
            if (isStale(mail)) {
                save(mail, attempt, "STALE", null);
                return MailOutcome.STALE;
            }

            try {
                sendTimed(mail);
                save(mail, attempt, "SUCCESS", null);
                return MailOutcome.SENT;
            } catch (RuntimeException e) {
                boolean last = !isRetryable(e) || attempt >= backoff.maxRetries();
                save(mail, attempt, last ? "GAVE_UP" : "FAILED", e.getMessage());
                if (last) {
                    return MailOutcome.GAVE_UP;
                }
            }
        }
    }

    /**
     * 다시 보내면 결과가 달라질 수 있는 실패인가.
     *
     * <ul>
     *   <li>SMTP가 응답 코드를 준 실패는 코드로 가른다 — 5xx(예: 없는 주소 550, 발송 한도)는 영구 실패,
     *       4xx(예: 일시적 과부하 421·451)는 일시 실패</li>
     *   <li>코드 없이 연결·읽기에서 실패한 경우(연결 거부, 타임아웃)는 일시 실패로 본다 — SMTP 장애가 풀리면 성공한다</li>
     *   <li>{@link MailSendException}이 아닌 실패(인증 오류 {@code MailAuthenticationException}, 메시지 조립 오류)는
     *       설정·데이터 문제라 다시 보내도 같다</li>
     * </ul>
     */
    static boolean isRetryable(RuntimeException e) {
        if (!(e instanceof MailSendException send)) {
            return false;
        }
        Integer code = smtpReturnCode(send);
        return code == null || code < 500;
    }

    private static Integer smtpReturnCode(MailSendException e) {
        for (Exception failure : e.getFailedMessages().values()) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof SMTPAddressFailedException a) {
                    return a.getReturnCode();
                }
                if (t instanceof SMTPSendFailedException s) {
                    return s.getReturnCode();
                }
            }
        }
        return null;
    }

    // 저장된 번호가 바뀌었거나(재발송) 사라졌으면(5분 만료) 이 메일은 보내 봐야 쓸 수 없다.
    // 저장소 장애로 확인하지 못하면 보낸다 — 확인 실패를 이유로 버리면 사용자는 아무 메일도 받지 못한다.
    private boolean isStale(Mail mail) {
        try {
            Optional<String> current = codeSource.currentCode(mail.to());
            return current.isEmpty() || !current.get().equals(mail.code());
        } catch (RuntimeException e) {
            log.warn("재시도 전 인증번호 확인에 실패해 그대로 재시도한다", e);
            return false;
        }
    }

    // MailLog는 발송의 기록이지 발송 자체가 아니다. 저장이 실패해도(DB 장애) 발송 흐름과 결말 집계는 그대로 간다 —
    // 예외를 올리면 SMTP 성공 뒤의 저장 실패가 메일을 결말 없이 끝내거나, 재시도 판단을 잘못 이끈다.
    private void save(Mail mail, int attempt, String status, String errorMessage) {
        try {
            mailLogRepository.save(MailLog.builder()
                .recipient(mail.to())
                .subject(mail.subject())
                .retryCount(attempt)
                .status(status)
                .errorMessage(truncate(errorMessage))
                .build());
        } catch (RuntimeException e) {
            log.error("MailLog 저장에 실패했다 — status={}, attempt={}", status, attempt, e);
        }
    }

    // 자르지 않으면 save()가 DataIntegrityViolationException으로 실패해, 그 예외가 원래의 발송 실패를
    // 덮어쓴다(트랜잭션 유무와 무관하다 — IDENTITY 전략이라 save()가 즉시 INSERT한다).
    private static String truncate(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX);
    }

    // SMTP 시도 한 번의 구간만 잰다 — 메시지 조립과 MailLog 저장은 넣지 않는다.
    // 예외 종류와 무관하게 던져졌으면 실패로 센다(재시도 여부 판단은 호출부의 몫이다).
    private void sendTimed(Mail mail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.body());
        message.setFrom("너의 여행은 <th2194@gmail.com>");

        long start = System.nanoTime();
        try {
            mailSender.send(message);
            mailMetrics.attempt(AttemptOutcome.SUCCESS, System.nanoTime() - start);
        } catch (RuntimeException e) {
            mailMetrics.attempt(AttemptOutcome.FAILURE, System.nanoTime() - start);
            throw e;
        }
    }
}
