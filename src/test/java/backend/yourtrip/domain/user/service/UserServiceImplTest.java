package backend.yourtrip.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import backend.yourtrip.global.mail.service.MailService;
import backend.yourtrip.global.s3.service.S3Service;
import backend.yourtrip.global.security.CustomUserDetails;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * logout()이 SecurityContext의 인증 사용자를 식별해 저장된 Refresh Token을
 * 무효화(null)하는지 검증한다. 재발급(refresh)이 이후 막히는 것까지는 이 테스트가 아니라
 * UserControllerE2ETest의 수동 검증 절차(계획 문서 참고)로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private MailService mailService;

    @Mock
    private S3Service s3Service;

    @Mock
    private CloudFrontService cloudFrontService;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("logout() - 인증된 사용자의 Refresh Token을 DB에서 null로 지운다")
    void logout_clearsRefreshToken() {
        // given
        Long userId = 1L;
        User user = User.builder()
            .id(userId)
            .email("user@example.com")
            .nickname("여행러버")
            .refreshToken("stored-refresh-token")
            .build();

        authenticateAs(user);

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        // when
        userService.logout();

        // then
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getRefreshToken()).isNull();
    }

    @Test
    @DisplayName("logout() - 인증 정보가 없으면 USER_NOT_FOUND 예외를 던진다")
    void logout_withoutAuthentication_throwsUserNotFound() {
        // given: SecurityContext에 인증 정보를 세팅하지 않음

        // when & then
        assertThatThrownBy(() -> userService.logout())
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("logout() - 토큰은 유효하나 사용자가 삭제된 경우 USER_NOT_FOUND 예외를 던진다")
    void logout_userDeleted_throwsUserNotFound() {
        // given
        Long userId = 1L;
        User user = User.builder()
            .id(userId)
            .email("user@example.com")
            .nickname("여행러버")
            .refreshToken("stored-refresh-token")
            .build();

        authenticateAs(user);

        given(userRepository.findById(userId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> userService.logout())
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_NOT_FOUND);
    }

    private void authenticateAs(User user) {
        CustomUserDetails userDetails = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }
}
