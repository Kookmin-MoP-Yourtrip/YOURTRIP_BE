package backend.yourtrip.domain.user.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Where;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "users")
@Where(clause = "deleted = false")
public class User extends BaseEntity {

    @Id
    @Column(name = "user_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    private String password;

    private String nickname;

    private String profileImageS3Key;

    @Builder.Default
    private boolean deleted = false;

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

    public User withRefreshToken(String refreshToken) {
        return this.toBuilder()
            .refreshToken(refreshToken)
            .build();
    }

    public User withProfileImage(String s3Key) {
        return this.toBuilder()
            .profileImageS3Key(s3Key)
            .build();
    }

    public User withNickname(String nickname) {
        return this.toBuilder()
            .nickname(nickname)
            .build();
    }

    public User withPassword(String encodedPassword) {
        return this.toBuilder()
            .password(encodedPassword)
            .build();
    }

    public User withDeleted() {
        return this.toBuilder()
            .deleted(true)
            .build();
    }

    public String getProfileImageUrl() {
        return this.profileImageS3Key;
    }
}