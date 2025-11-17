package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.*;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import backend.yourtrip.global.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider jwtTokenProvider;
    private final MailService mailService;

    // 회원가입용 인증
    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> codeExpiry = new ConcurrentHashMap<>();
    private final Set<String> verifiedEmails = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> tempPasswords = new ConcurrentHashMap<>();

    // 비밀번호 찾기용 인증
    private final Map<String, String> findPwCodes = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> findPwExpiry = new ConcurrentHashMap<>();
    private final Set<String> findPwVerifiedEmails = Collections.synchronizedSet(new HashSet<>());

    private static final int CODE_EXPIRY_MINUTES = 5;
    private static final String DEFAULT_PROFILE_IMAGE =
        "https://yourtrip.s3.ap-northeast-2.amazonaws.com/default_profile.png";



    // ======================================
    // 회원가입 1단계: 인증번호 발송
    // ======================================
    @Override
    public void sendVerificationCode(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        verificationCodes.put(email, code);
        codeExpiry.put(email, LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES));

        mailService.sendVerificationMail(email, code);
        System.out.println("[회원가입 인증번호 전송] " + email);
    }


    // ======================================
    // 회원가입 2단계: 인증번호 검증
    // ======================================
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
        System.out.println("[회원가입 이메일 인증 완료] " + email);
    }


    // ======================================
    // 회원가입 3단계: 비밀번호 임시 저장
    // ======================================
    @Override
    public void setPassword(String email, String password) {
        if (!verifiedEmails.contains(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (password == null || password.isBlank() || password.length() < 8) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }

        tempPasswords.put(email, passwordEncoder.encode(password));
        System.out.println("[회원가입 비밀번호 임시 저장] " + email);
    }


    // ======================================
    // 회원가입 4단계: 프로필 등록 & 최종 생성
    // ======================================
    @Transactional
    @Override
    public UserSignupResponse completeSignup(ProfileCreateRequest request) {
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

        String imageUrl =
            (request.profileImageUrl() != null && !request.profileImageUrl().isBlank())
                ? request.profileImageUrl()
                : DEFAULT_PROFILE_IMAGE;

        User user = User.builder()
            .email(email)
            .password(encodedPw)
            .nickname(request.nickname())
            .profileImageUrl(imageUrl)
            .emailVerified(true)
            .deleted(false)
            .build();

        user = userRepository.save(user);

        // 임시정보 삭제
        verifiedEmails.remove(email);
        tempPasswords.remove(email);
        verificationCodes.remove(email);
        codeExpiry.remove(email);

        System.out.println("[회원가입 완료] " + email);
        return UserMapper.toSignupResponse(user);
    }



    // ======================================
    // 로그인
    // ======================================
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



    // ======================================
    // 액세스토큰 재발급
    // ======================================
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



    // ======================================
    // 현재 로그인유저 조회
    // ======================================
    @Override
    public User getUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if (principal instanceof Long id) return id;
        throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
    }



    // ======================================
    // 카카오 로그인/회원가입 통합
    // ======================================
    @Transactional
    @Override
    public UserLoginResponse kakaoLoginOrSignup(String kakaoId, String email, String nickname, String profileImageUrl) {
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



    // ======================================
    // 비밀번호 찾기 1단계: 이메일 인증번호 발송
    // ======================================
    @Override
    public void findPasswordSendEmail(String email) {
        userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_NOT_FOUND));

        String code = String.format("%06d", new Random().nextInt(1_000_000));
        findPwCodes.put(email, code);
        findPwExpiry.put(email, LocalDateTime.now().plusMinutes(CODE_EXPIRY_MINUTES));

        mailService.sendPasswordResetMail(email, code);
        System.out.println("[비밀번호 찾기 인증번호 전송] " + email);
    }



    // ======================================
    // 비밀번호 찾기 2단계: 인증번호 검증
    // ======================================
    @Override
    public void findPasswordVerify(String email, String code) {
        String saved = findPwCodes.get(email);
        LocalDateTime expiry = findPwExpiry.get(email);

        if (saved == null || expiry == null) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }

        if (LocalDateTime.now().isAfter(expiry)) {
            findPwCodes.remove(email);
            findPwExpiry.remove(email);
            throw new BusinessException(UserErrorCode.VERIFICATION_CODE_EXPIRED);
        }

        if (!saved.equals(code)) {
            throw new BusinessException(UserErrorCode.INVALID_VERIFICATION_CODE);
        }

        findPwVerifiedEmails.add(email);
        System.out.println("[비밀번호 찾기 이메일 인증 완료] " + email);
    }



    // ======================================
    // 비밀번호 찾기 3단계: 새 비밀번호 저장
    // ======================================
    @Transactional
    @Override
    public void resetPassword(String email, String newPassword) {

        if (!findPwVerifiedEmails.contains(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (newPassword == null || newPassword.isBlank() || newPassword.length() < 8) {
            throw new BusinessException(UserErrorCode.INVALID_REQUEST_FIELD);
        }

        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new BusinessException(UserErrorCode.EMAIL_NOT_FOUND));

        user = user.toBuilder()
            .password(passwordEncoder.encode(newPassword))
            .build();
        userRepository.save(user);

        // 상태 초기화
        findPwCodes.remove(email);
        findPwExpiry.remove(email);
        findPwVerifiedEmails.remove(email);

        System.out.println("[비밀번호 재설정 완료] " + email);
    }
}