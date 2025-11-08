package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.response.KakaoTokenResponse;
import backend.yourtrip.domain.user.dto.response.KakaoProfileResponse;
import backend.yourtrip.domain.user.dto.response.KakaoUserResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.util.KakaoUtil;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KakaoService {

    private final KakaoUtil kakaoUtil;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public KakaoUserResponse kakaoLogin(String code) {
        KakaoTokenResponse tokenResponse = kakaoUtil.requestToken(code);
        KakaoProfileResponse profile = kakaoUtil.requestProfile(tokenResponse.accessToken());

        String kakaoId = String.valueOf(profile.id());
        String email = profile.kakaoAccount().email();
        String nickname = profile.kakaoAccount().profile().nickname();
        String profileImageUrl = profile.kakaoAccount().profile().profileImageUrl();

        User user = userRepository.findByEmail(email)
            .orElseGet(() -> userRepository.save(
                UserMapper.toKakaoEntity(kakaoId, email, nickname, profileImageUrl)
            ));

        String jwtAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());

        return new KakaoUserResponse(
            user.getId(),
            user.getEmail(),
            user.getNickname(),
            user.getProfileImageUrl(),
            jwtAccessToken
        );
    }
}
