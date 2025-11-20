package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.request.NicknameChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.mypage.dto.response.ProfileResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ProfileService {

    ProfileResponse getProfile();

    ProfileImageResponse updateProfileImage(MultipartFile file);

    void updateNickname(String nickname);

    void changePassword(PasswordChangeRequest request);

    void deleteUser();
}