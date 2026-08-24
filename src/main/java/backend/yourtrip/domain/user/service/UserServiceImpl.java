package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.ProfileCreateRequest;
import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.store.EmailVerificationStore;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import backend.yourtrip.global.mail.service.MailService;
import backend.yourtrip.global.s3.service.S3Service;
import backend.yourtrip.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Random;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider jwtTokenProvider;
    private final MailService mailService;
    private final S3Service s3Service;
    private final CloudFrontService cloudFrontService;

    // 인증코드·인증완료·임시비밀번호 상태는 전부 이 저장소(Redis + TTL)가 관리한다.
    // 재기동 생존과 다중 인스턴스(ALB) 안전성을 위해 인스턴스 로컬 상태를 두지 않는다(#117).
    private final EmailVerificationStore emailVerificationStore;

    @Override
    public void sendVerificationCode(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        // 저장 성공 후에만 발송한다 — 저장 실패(503) 시 메일이 나가면
        // 검증이 영원히 불가능한 코드가 사용자에게 전달되는 고아 메일이 된다.
        emailVerificationStore.saveCode(email, code);

        mailService.sendVerificationMail(email, code);

        System.out.println("[인증번호 전송 완료] " + email);
    }

    @Override
    public void verifyCode(String email, String code) {
        // TTL 만료로 사라진 코드는 미발급과 구분되지 않는다 — 둘 다 INVALID_VERIFICATION_CODE.
        String stored = emailVerificationStore.findCode(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE));

        if (!stored.equals(code)) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }

        emailVerificationStore.markVerified(email);
        System.out.println("[이메일 인증 완료] " + email);
    }

    @Override
    public void setPassword(String email, String password) {
        if (!emailVerificationStore.isVerified(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (password == null || password.isBlank() || password.length() < 8) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }

        String encoded = passwordEncoder.encode(password);
        emailVerificationStore.saveTempPassword(email, encoded);
    }

    @Transactional
    @Override
    public UserSignupResponse completeSignup(ProfileCreateRequest request,
        MultipartFile profileImage) {
        String email = request.email();

        if (!emailVerificationStore.isVerified(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }
        String encodedPw = emailVerificationStore.findTempPassword(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD));
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

        String profileImageS3Key;

        if (profileImage != null) {
            try {
                profileImageS3Key = s3Service.uploadFile(profileImage).key();
            } catch (IOException e) {
                throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
            }
        } else {
            profileImageS3Key = "default-profile.png";
        }

        User user = User.builder()
            .email(email)
            .password(encodedPw)
            .nickname(request.nickname())
            .emailVerified(true)
            .profileImageS3Key(profileImageS3Key)
            .deleted(false)
            .build();

        user = userRepository.save(user);

        emailVerificationStore.clear(email);

        String profileUrl = cloudFrontService.getPublicUrl(user.getProfileImageS3Key());

        return UserMapper.toSignupResponse(user, profileUrl);
    }

    @Transactional
    @Override
    public UserLoginResponse login(UserLoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_NOT_FOUND));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(UserErrorCode.NOT_MATCH_PASSWORD);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());

        user = user.withRefreshToken(refreshToken);
        userRepository.save(user);

        String profileUrl = cloudFrontService.getPublicUrl(user.getProfileImageS3Key());

        return new UserLoginResponse(
            user.getId(),
            user.getNickname(),
            profileUrl,
            accessToken
        );
    }


    @Transactional(readOnly = true)
    @Override
    public UserLoginResponse refresh(String refreshToken) {

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (!"refresh".equals(jwtTokenProvider.getTokenType(refreshToken))) {
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        User user = getUser(userId);

        if (user.getRefreshToken() == null || !user.getRefreshToken().equals(refreshToken)) {
            throw new BusinessException(UserErrorCode.NOT_MATCH_REFRESH_TOKEN);
        }

        String profileUrl = cloudFrontService.getPublicUrl(user.getProfileImageS3Key());
        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());

        return new UserLoginResponse(user.getId(), user.getNickname(), profileUrl, newAccessToken);
    }

    @Transactional(readOnly = true)
    @Override
    public User getUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    @Transactional
    @Override
    public void logout() {
        Long userId = getCurrentUserId();
        User user = getUser(userId);

        user = user.withRefreshToken(null);
        userRepository.save(user);
    }

    @Override
    public Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication() != null
            ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            : null;

        if (principal instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
    }

    @Override
    public Long getCurrentUserIdOrNull() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication() != null
            ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            : null;

        if (principal instanceof CustomUserDetails userDetails) {
            return userDetails.getUserId();
        }
        return null;
    }

    @Override
    public void findPasswordSendEmail(String email) {
        userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_NOT_FOUND));

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        // sendVerificationCode와 동일하게 저장 성공 후에만 발송한다.
        emailVerificationStore.saveCode(email, code);

        mailService.sendVerificationMail(email, code);
    }

    @Override
    public void findPasswordVerify(String email, String code) {
        String stored = emailVerificationStore.findCode(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE));

        if (!stored.equals(code)) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }

        emailVerificationStore.markVerified(email);
    }

    @Override
    public void resetPassword(String email, String newPassword) {

        if (!emailVerificationStore.isVerified(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_NOT_FOUND));

        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }

        String encoded = passwordEncoder.encode(newPassword);
        user = user.withPassword(encoded);
        userRepository.save(user);

        emailVerificationStore.clear(email);
    }
}