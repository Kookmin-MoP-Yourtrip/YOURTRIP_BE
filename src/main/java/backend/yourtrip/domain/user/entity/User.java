package backend.yourtrip.domain.user.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Builder
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

    // -----------------------------------------------------------------
    // 상태 변경은 영속 상태의 이 인스턴스를 직접 바꾸고 dirty checking에 맡긴다.
    //
    // 과거에는 toBuilder()로 새 인스턴스를 만들어 돌려주는 with* 메서드를 쓰고
    // 호출부가 save()로 merge했다. 그런데 Lombok @Builder는 @SuperBuilder와 달리
    // 상위 클래스(BaseEntity)의 필드를 복사하지 않는다. 그래서 복사본은 createdAt이
    // null이었고, merge가 그 null을 UPDATE 문에 실어 DB까지 내려보냈다(#136).
    // updatedAt은 @LastModifiedDate가 다시 채우지만 createdAt은 @CreatedDate라
    // 최초 저장에만 동작해 복구되지 않는다.
    //
    // toBuilder = true를 함께 제거한 것은 의도적이다. 뮤테이터만 추가하고 toBuilder를
    // 남겨두면 같은 함정을 다음 사람이 다시 밟는다.
    // -----------------------------------------------------------------

    public void updateRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    /** 로그아웃은 "null로 갱신"이 아니라 "무효화"라는 의도를 호출부에서 읽히게 한다. */
    public void clearRefreshToken() {
        this.refreshToken = null;
    }

    public void updateProfileImage(String s3Key) {
        this.profileImageS3Key = s3Key;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    /**
     * 중복 삭제 가드는 여기 두지 않는다. User가 MypageErrorCode를 던지면 user 도메인에서
     * mypage 도메인으로 역방향 의존이 생긴다. 가드는 지금 있는 자리(ProfileServiceImpl)에 둔다.
     */
    public void softDelete() {
        this.deleted = true;
    }

    public String getProfileImageUrl() {
        return this.profileImageS3Key;
    }
}