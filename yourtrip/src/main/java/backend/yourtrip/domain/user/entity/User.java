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

    private String email;
    private String password;
    private String nickname;

    // S3에 저장된 프로필 이미지 key (URL 저장도 가능)
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

    /** 프로필 이미지 URL 또는 S3 Key 변경 */
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