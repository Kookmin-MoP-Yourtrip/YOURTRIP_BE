package backend.yourtrip.domain.user.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;

import lombok.*;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "users")
@SQLRestriction("deleted = false")
public class User extends BaseEntity {

    @Id
    @Column(name = "user_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String password;

    private String nickname;

    private String profileImageUrl;

    private boolean deleted;

    private String refreshToken;

    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AuthProvider provider = AuthProvider.LOCAL;

    @Column(unique = true)
    private String socialId;
}
