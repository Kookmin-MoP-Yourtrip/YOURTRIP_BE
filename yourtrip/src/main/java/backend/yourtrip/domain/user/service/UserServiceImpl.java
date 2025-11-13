package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.ProfileCreateRequest;
import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.entity.UserRole;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import backend.yourtrip.global.mail.service.MailService;
import backend.yourtrip.global.s3.service.S3ImageUploadService;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider jwtTokenProvider;
    private final MailService mailService;

    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> codeExpiry = new ConcurrentHashMap<>();
    private final Set<String> verifiedEmails = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> tempPasswords = new ConcurrentHashMap<>();

    private static final int CODE_EXPIRY_MINUTES = 5;
    private static final String DEFAULT_PROFILE_IMAGE =
        "https://yourtrip.s3.ap-northeast-2.amazonaws.com/default_profile.png";

    private final S3ImageUploadService uploadService;

    @Override
    public void sendVerificationCode(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        verificationCodes.put(email, code);
        codeExpiry.put(email, LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES));

        mailService.sendVerificationMail(email, code);

        System.out.println("[인증번호 전송 완료] " + email);
    }

    @Override
    public void verifyCode(String email, String code) {
        String stored = verificationCodes.get(email);
        LocalDateTime expiry = codeExpiry.get(email);

        if (stored == null || expiry == null) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }
        if (LocalDateTime.now().isAfter(expiry)) {
            verificationCodes.remove(email);
            codeExpiry.remove(email);
            throw new BusinessException(UserErrorCode.VERIFICATION_CODE_EXPIRED);
        }
        if (!stored.equals(code)) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }

        verifiedEmails.add(email);
        System.out.println("[이메일 인증 완료] " + email);
    }

    @Override
    public void setPassword(String email, String password) {
        if (!verifiedEmails.contains(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (password == null || password.isBlank() || password.length() < 8) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }

        String encoded = passwordEncoder.encode(password);
        tempPasswords.put(email, encoded);
        System.out.println("[비밀번호 임시 저장 완료] " + email);
    }

    @Transactional
    @Override
    public UserSignupResponse completeSignup(ProfileCreateRequest request,
        MultipartFile profileImage) {
        String email = request.email();

        if (!verifiedEmails.contains(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }
        String encodedPw = tempPasswords.get(email);
        if (encodedPw == null) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

//        String imageUrl =
//            (request.profileImageUrl() != null && !request.profileImageUrl().isBlank())
//                ? request.profileImageUrl()
//                : DEFAULT_PROFILE_IMAGE;

        String profileImageUrl;
        try {
            profileImageUrl = uploadService.uploadImage(profileImage).url();
        } catch (IOException e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        User user = User.builder()
            .email(email)
            .password(encodedPw)
            .nickname(request.nickname())
//            .profileImageUrl(imageUrl)
            .emailVerified(true)
            .profileImageUrl(profileImageUrl)
            .deleted(false)
            .build();

        user = userRepository.save(user);

        verifiedEmails.remove(email);
        tempPasswords.remove(email);
        verificationCodes.remove(email);
        codeExpiry.remove(email);

        System.out.println("[회원가입 완료] " + user.getEmail());

        return UserMapper.toSignupResponse(user);
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

        user = user.toBuilder().refreshToken(refreshToken).build();
        userRepository.save(user);

        return new UserLoginResponse(user.getId(), user.getNickname(), accessToken);
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

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        return new UserLoginResponse(user.getId(), user.getNickname(), newAccessToken);
    }

    @Transactional(readOnly = true)
    @Override
    public User getUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication() != null
            ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
            : null;

        if (principal instanceof Long id) {
            return id;
        }
        throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
    }

    @Transactional
    @Override
    public UserLoginResponse kakaoLoginOrSignup(String kakaoId, String email, String nickname,
        String profileImageUrl) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            user = UserMapper.toKakaoTemp(kakaoId, email, profileImageUrl)
                .toBuilder()
                .nickname(nickname)
                .role(UserRole.USER)
                .build();
            user = userRepository.save(user);
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());

        user = user.toBuilder().refreshToken(refreshToken).build();
        userRepository.save(user);

        return new UserLoginResponse(user.getId(), user.getNickname(), accessToken);
    }
}