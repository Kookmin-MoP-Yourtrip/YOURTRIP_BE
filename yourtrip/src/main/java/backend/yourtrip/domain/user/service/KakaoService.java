package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.entity.UserRole;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.dto.response.KakaoLoginInitResponse;
import backend.yourtrip.domain.user.service.dto.response.KakaoProfileResponse;
import backend.yourtrip.domain.user.service.dto.response.KakaoTokenResponse;
import backend.yourtrip.domain.user.service.util.KakaoUtil;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoService {

    private final KakaoUtil kakaoUtil;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public KakaoLoginInitResponse init(String code) {
        KakaoTokenResponse tokenResponse = kakaoUtil.requestToken(code);
        KakaoProfileResponse profile = kakaoUtil.requestProfile(tokenResponse.accessToken());

        String kakaoId = String.valueOf(profile.id());
        String email = profile.kakaoAccount().email();
        String safeEmail = (email != null && !email.isBlank())
            ? email
            : kakaoId + "@kakao-temp.local";

        String image = profile.kakaoAccount().profile() != null
            ? profile.kakaoAccount().profile().profileImageUrl()
            : null;

        User existing = userRepository.findBySocialId(kakaoId)
            .or(() -> userRepository.findByEmail(safeEmail))
            .filter(u -> u.getRole() == UserRole.USER)
            .orElse(null);

        if (existing != null) {
            String at = jwtTokenProvider.createAccessToken(existing.getId(), existing.getEmail());
            return new KakaoLoginInitResponse(
                "EXISTING",
                kakaoId,
                safeEmail,
                image,
                new UserLoginResponse(existing.getId(), existing.getNickname(), at)
            );
        }

        userRepository.findBySocialId(kakaoId)
            .orElseGet(
                () -> userRepository.save(UserMapper.toKakaoTemp(kakaoId, safeEmail, image)));

        return new KakaoLoginInitResponse("NEED_PROFILE", kakaoId, safeEmail, image, null);
    }

    @Transactional
    public UserLoginResponse complete(String kakaoId, String nickname, String profileImageUrl) {
        User temp = userRepository.findBySocialId(kakaoId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        temp = temp.toBuilder()
            .nickname(nickname)
            .profileImageS3Key(profileImageUrl)
            .role(UserRole.USER)
            .build();
        temp = userRepository.save(temp);

        String at = jwtTokenProvider.createAccessToken(temp.getId(), temp.getEmail());
        String rt = jwtTokenProvider.createRefreshToken(temp.getId(), temp.getEmail());
        temp = userRepository.save(temp.toBuilder().refreshToken(rt).build());

        log.info("[KakaoService] USER 전환 완료: {} ({})", temp.getNickname(), temp.getEmail());
        return new UserLoginResponse(temp.getId(), temp.getNickname(), at);
    }
}