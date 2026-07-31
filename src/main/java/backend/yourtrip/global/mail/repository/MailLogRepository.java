package backend.yourtrip.global.mail.repository;

import backend.yourtrip.global.mail.entity.MailLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailLogRepository extends JpaRepository<MailLog, Long> {
}