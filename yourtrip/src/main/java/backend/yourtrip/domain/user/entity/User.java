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

    public String getProfileImageUrl() {
        return this.profileImageS3Key;
    }

    /** 프로필 이미지 URL 변경 */
    public void updateProfileImage(String profileUrl) {
        this.profileImageS3Key = profileUrl;
    }

    /** 닉네임 변경 */
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 비밀번호 변경 */
    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /** Soft delete 처리 */
    public void deleteUser() {
        this.deleted = true;
    }
}