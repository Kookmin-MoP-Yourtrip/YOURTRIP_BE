package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.mypage.dto.response.ProfileResponse;
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
public class ProfileServiceImpl implements ProfileService {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final S3Service s3Service;

    @Override
    @Transactional(readOnly = true)
    public ProfileResponse getProfile() {
        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        String url = s3Service.getPresignedUrl(user.getProfileImageS3Key());

        return new ProfileResponse(
            user.getEmail(),
            user.getNickname(),
            url
        );
    }

    @Override
    @Transactional
    public ProfileImageResponse updateProfileImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(MypageErrorCode.INVALID_PROFILE_IMAGE);
        }

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        try {
            String key = s3Service.uploadFile(file).key();

            user = user.withProfileImage(key);
            userRepository.save(user);

            String presigned = s3Service.getPresignedUrl(key);
            return new ProfileImageResponse(presigned);

        } catch (IOException e) {
            throw new BusinessException(MypageErrorCode.PROFILE_IMAGE_UPLOAD_FAILED);
        }
    }

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

        user = user.withNickname(nickname);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void changePassword(PasswordChangeRequest request) {
        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new BusinessException(MypageErrorCode.PASSWORD_INCORRECT);
        }

        if (request.newPassword().length() < 8) {
            throw new BusinessException(MypageErrorCode.NEW_PASSWORD_INVALID);
        }

        user = user.withPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteUser() {

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        if (user.isDeleted()) {
            throw new BusinessException(MypageErrorCode.ALREADY_DELETED_USER);
        }

        user = user.withDeleted();
        userRepository.save(user);
    }
}