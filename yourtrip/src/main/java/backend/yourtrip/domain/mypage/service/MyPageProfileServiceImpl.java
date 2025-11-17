package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MypageErrorCode;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import backend.yourtrip.global.s3.service.S3Service;
import backend.yourtrip.global.s3.service.S3Service.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MyPageProfileServiceImpl implements MyPageProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final S3Service s3Service;

    /** 현재 로그인 사용자 조회 */
    private User getCurrentUser() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    // ===========================
    // 1. 프로필 이미지 업로드
    // ===========================
    @Override
    @Transactional
    public ProfileImageResponse updateProfileImage(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new BusinessException(MypageErrorCode.INVALID_PROFILE_IMAGE);
        }

        User user = getCurrentUser();

        try {
            UploadResult result = s3Service.uploadFile(file);

            user = user.toBuilder()
                .profileImageS3Key(result.key())
                .build();

            userRepository.save(user);

            String presignedUrl = s3Service.getPresignedUrl(result.key());

            return new ProfileImageResponse(presignedUrl);

        } catch (Exception e) {
            throw new BusinessException(MypageErrorCode.PROFILE_IMAGE_UPLOAD_FAILED);
        }
    }

    // ===========================
    // 2. 닉네임 변경
    // ===========================
    @Override
    @Transactional
    public void updateNickname(String nickname) {

        if (nickname == null || nickname.isBlank() || nickname.length() > 20) {
            throw new BusinessException(MypageErrorCode.INVALID_NICKNAME);
        }

        User user = getCurrentUser();

        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(MypageErrorCode.NICKNAME_DUPLICATED);
        }

        user = user.toBuilder()
            .nickname(nickname)
            .build();

        userRepository.save(user);
    }

    // ===========================
    // 3. 비밀번호 변경
    // ===========================
    @Override
    @Transactional
    public void changePassword(PasswordChangeRequest req) {

        User user = getCurrentUser();

        if (!passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            throw new BusinessException(MypageErrorCode.PASSWORD_INCORRECT);
        }

        if (req.newPassword().length() < 8) {
            throw new BusinessException(MypageErrorCode.NEW_PASSWORD_INVALID);
        }

        user = user.toBuilder()
            .password(passwordEncoder.encode(req.newPassword()))
            .build();

        userRepository.save(user);
    }

    // ===========================
    // 4. 회원 탈퇴
    // ===========================
    @Override
    @Transactional
    public void deleteUser() {

        User user = getCurrentUser();

        if (user.isDeleted()) {
            throw new BusinessException(MypageErrorCode.ALREADY_DELETED_USER);
        }

        user = user.toBuilder()
            .deleted(true)
            .build();

        userRepository.save(user);
    }
}