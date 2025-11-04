package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.CustomException;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    @Override
    public UserSignupResponse signup(UserSignupRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new CustomException("이미 가입된 이메일입니다.");
        }

        User user = UserMapper.toEntity(request, passwordEncoder);

        user = userRepository.save(user);

        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());

        user = user.toBuilder()
            .refreshToken(refreshToken)
            .build();
        userRepository.save(user);

        return UserMapper.toSignupResponse(user);
    }

    @Transactional
    @Override
    public UserLoginResponse login(UserLoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException("존재하지 않는 이메일입니다."));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException("비밀번호가 일치하지 않습니다.");
        }

        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), user.getEmail());

        user = user.toBuilder()
            .refreshToken(refreshToken)
            .build();

        userRepository.save(user);

        return new UserLoginResponse(user.getId(), user.getNickname(), accessToken);
    }

    @Transactional(readOnly = true)
    @Override
    public UserLoginResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new CustomException("유효하지 않은 리프레시 토큰입니다.");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new CustomException("사용자를 찾을 수 없습니다."));

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());

        return new UserLoginResponse(user.getId(), user.getNickname(), newAccessToken);
    }
}