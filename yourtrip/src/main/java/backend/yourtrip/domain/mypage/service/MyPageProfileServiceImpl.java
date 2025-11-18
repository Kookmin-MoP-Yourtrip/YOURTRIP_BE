package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MypageErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class MyPageProfileServiceImpl implements MyPageProfileService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final PasswordEncoder passwordEncoder;


    // =========================================================
    // 1. 프로필 이미지 업로드
    // =========================================================
    @Override
    @Transactional
    public ProfileImageResponse updateProfileImage(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new BusinessException(MypageErrorCode.INVALID_PROFILE_IMAGE);
        }

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        // S3 업로드
        String uploadedUrl;
        try {
            uploadedUrl = s3Service.uploadFile(file).url();
        } catch (IOException e) {
            throw new BusinessException(MypageErrorCode.PROFILE_IMAGE_UPLOAD_FAILED);
        }

        // User 엔티티 업데이트
        user.updateProfileImage(uploadedUrl);

        return new ProfileImageResponse(uploadedUrl);
    }


    // =========================================================
    // 2. 닉네임 변경
    // =========================================================
    @Override
    @Transactional
    public void updateNickname(String nickname) {

        if (nickname == null || nickname.trim().isEmpty() || nickname.length() > 20) {
            throw new BusinessException(MypageErrorCode.INVALID_NICKNAME);
        }

        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(MypageErrorCode.NICKNAME_DUPLICATED);
        }

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        user.updateNickname(nickname);
    }


    // =========================================================
    // 3. 비밀번호 변경
    // =========================================================
    @Override
    @Transactional
    public void changePassword(PasswordChangeRequest request) {

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        // 현재 비밀번호 검증
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessException(MypageErrorCode.PASSWORD_INCORRECT);
        }

        // 새 비밀번호 정책 검사
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new BusinessException(MypageErrorCode.NEW_PASSWORD_INVALID);
        }

        user.updatePassword(passwordEncoder.encode(request.newPassword()));
    }


    // =========================================================
    // 4. 회원 탈퇴 (Soft Delete)
    // =========================================================
    @Override
    @Transactional
    public void deleteUser() {

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        if (user.isDeleted()) {
            throw new BusinessException(MypageErrorCode.ALREADY_DELETED_USER);
        }

        user.deleteUser();
    }
}