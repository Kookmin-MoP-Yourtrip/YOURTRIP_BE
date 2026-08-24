package backend.yourtrip.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.user.dto.request.ProfileCreateRequest;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.store.EmailVerificationStore;
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
import org.mockito.InOrder;
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
 * <p>
 * 이메일 인증 흐름(#117)은 EmailVerificationStore mock으로 서비스 로직만 검증한다 —
 * 저장·발송 순서, 코드 검증 분기, fail-closed 시 메일 미발송. 컨트롤러를 관통하는
 * 발송→확인 흐름은 EmailVerificationFlowTest, Redis 장애 응답은
 * EmailVerificationFailClosedTest가 커버한다.
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

    @Mock
    private EmailVerificationStore emailVerificationStore;

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

    // =========================================================
    // 이메일 인증 흐름 (#117 — EmailVerificationStore 기반)
    // =========================================================

    @Test
    @DisplayName("sendVerificationCode() - 이미 가입된 이메일이면 EMAIL_ALREADY_EXIST 예외를 던지고 아무것도 저장/발송하지 않는다")
    void sendVerificationCode_alreadyRegistered_throwsEmailAlreadyExist() {
        String email = "existing@example.com";
        given(userRepository.findByEmail(email))
            .willReturn(Optional.of(User.builder().email(email).build()));

        assertThatThrownBy(() -> userService.sendVerificationCode(email))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.EMAIL_ALREADY_EXIST);

        verify(emailVerificationStore, never()).saveCode(anyString(), anyString());
        verify(mailService, never()).sendVerificationMail(anyString(), anyString());
    }

    @Test
    @DisplayName("sendVerificationCode() - 코드를 저장소에 저장한 뒤, 같은 코드를 메일로 발송한다")
    void sendVerificationCode_savesCodeThenSendsSameCode() {
        String email = "new@example.com";
        given(userRepository.findByEmail(email)).willReturn(Optional.empty());

        userService.sendVerificationCode(email);

        // 저장 실패 시 고아 메일이 나가면 안 되므로 "저장 → 발송" 순서 자체를 검증한다
        ArgumentCaptor<String> savedCode = ArgumentCaptor.forClass(String.class);
        InOrder inOrder = inOrder(emailVerificationStore, mailService);
        inOrder.verify(emailVerificationStore).saveCode(eq(email), savedCode.capture());
        inOrder.verify(mailService).sendVerificationMail(email, savedCode.getValue());

        assertThat(savedCode.getValue()).matches("\\d{6}");
    }

    @Test
    @DisplayName("sendVerificationCode() - 저장소(Redis) 장애 시 503 예외가 전파되고 메일은 발송되지 않는다 (fail-closed)")
    void sendVerificationCode_storeUnavailable_mailNotSent() {
        String email = "new@example.com";
        given(userRepository.findByEmail(email)).willReturn(Optional.empty());
        willThrow(new BusinessException(UserErrorCode.VERIFICATION_SERVICE_UNAVAILABLE))
            .given(emailVerificationStore).saveCode(eq(email), anyString());

        assertThatThrownBy(() -> userService.sendVerificationCode(email))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.VERIFICATION_SERVICE_UNAVAILABLE);

        verify(mailService, never()).sendVerificationMail(anyString(), anyString());
    }

    @Test
    @DisplayName("verifyCode() - 코드가 없으면(만료 포함) INVALID_VERIFICATION_CODE 예외를 던진다")
    void verifyCode_codeMissing_throwsInvalid() {
        String email = "new@example.com";
        given(emailVerificationStore.findCode(email)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.verifyCode(email, "123456"))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_VERIFICATION_CODE);

        verify(emailVerificationStore, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("verifyCode() - 코드가 일치하지 않으면 INVALID_VERIFICATION_CODE 예외를 던진다")
    void verifyCode_codeMismatch_throwsInvalid() {
        String email = "new@example.com";
        given(emailVerificationStore.findCode(email)).willReturn(Optional.of("123456"));

        assertThatThrownBy(() -> userService.verifyCode(email, "000000"))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.INVALID_VERIFICATION_CODE);

        verify(emailVerificationStore, never()).markVerified(anyString());
    }

    @Test
    @DisplayName("verifyCode() - 코드가 일치하면 인증 완료 상태로 표시한다")
    void verifyCode_success_marksVerified() {
        String email = "new@example.com";
        given(emailVerificationStore.findCode(email)).willReturn(Optional.of("123456"));

        userService.verifyCode(email, "123456");

        verify(emailVerificationStore).markVerified(email);
    }

    @Test
    @DisplayName("setPassword() - 인증되지 않은 이메일이면 EMAIL_NOT_VERIFIED 예외를 던진다")
    void setPassword_notVerified_throwsEmailNotVerified() {
        String email = "new@example.com";
        given(emailVerificationStore.isVerified(email)).willReturn(false);

        assertThatThrownBy(() -> userService.setPassword(email, "Abcd1234!"))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.EMAIL_NOT_VERIFIED);

        verify(emailVerificationStore, never()).saveTempPassword(anyString(), anyString());
    }

    @Test
    @DisplayName("setPassword() - 인증된 이메일이면 인코딩된 비밀번호를 저장소에 저장한다")
    void setPassword_success_savesEncodedTempPassword() {
        String email = "new@example.com";
        given(emailVerificationStore.isVerified(email)).willReturn(true);
        given(passwordEncoder.encode("Abcd1234!")).willReturn("encoded-pw");

        userService.setPassword(email, "Abcd1234!");

        verify(emailVerificationStore).saveTempPassword(email, "encoded-pw");
    }

    @Test
    @DisplayName("completeSignup() - 가입을 완료하면 사용자를 저장하고 인증 상태를 일괄 삭제한다")
    void completeSignup_success_savesUserAndClearsStore() {
        String email = "new@example.com";
        given(emailVerificationStore.isVerified(email)).willReturn(true);
        given(emailVerificationStore.findTempPassword(email)).willReturn(Optional.of("encoded-pw"));
        given(userRepository.findByEmail(email)).willReturn(Optional.empty());
        given(userRepository.save(any(User.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
        given(cloudFrontService.getPublicUrl("default-profile.png"))
            .willReturn("https://cdn.example.com/default-profile.png");

        UserSignupResponse response =
            userService.completeSignup(new ProfileCreateRequest(email, "여행러버"), null);

        assertThat(response.email()).isEqualTo(email);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded-pw");
        verify(emailVerificationStore).clear(email);
    }

    @Test
    @DisplayName("resetPassword() - 비밀번호를 갱신하면 인증 상태를 일괄 삭제한다")
    void resetPassword_success_updatesPasswordAndClearsStore() {
        String email = "user@example.com";
        User user = User.builder().id(1L).email(email).password("old-pw").build();
        given(emailVerificationStore.isVerified(email)).willReturn(true);
        given(userRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.encode("NewPass123!")).willReturn("encoded-new");

        userService.resetPassword(email, "NewPass123!");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("encoded-new");
        verify(emailVerificationStore).clear(email);
    }

    private void authenticateAs(User user) {
        CustomUserDetails userDetails = new CustomUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
        );
    }
}
