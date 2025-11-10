package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.dto.response.KakaoProfileResponse;
import backend.yourtrip.domain.user.service.dto.response.KakaoTokenResponse;
import backend.yourtrip.domain.user.service.util.KakaoUtil;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KakaoService {

    private final KakaoUtil kakaoUtil;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public UserLoginResponse kakaoLogin(String code, String nickname) {
        KakaoTokenResponse tokenResponse = kakaoUtil.requestToken(code);
        KakaoProfileResponse profile = kakaoUtil.requestProfile(tokenResponse.accessToken());

        String kakaoId = String.valueOf(profile.id());
        String email = profile.kakaoAccount().email();

        User user = userRepository.findByEmail(email)
            .orElseGet(() -> userRepository.save(
                UserMapper.toKakaoEntity(kakaoId, email, nickname, profile.kakaoAccount().profile().profileImageUrl())
            ));

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        return UserMapper.toLoginResponse(user, accessToken);
    }
}