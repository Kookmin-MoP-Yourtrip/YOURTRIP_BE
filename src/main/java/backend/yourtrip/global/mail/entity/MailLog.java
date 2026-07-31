package backend.yourtrip.global.mail.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "mail_logs")
public class MailLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(length = 10)
    private String status;

    @Column(length = 255)
    private String errorMessage;

    @Column(nullable = false)
    private int retryCount;
}