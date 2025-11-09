package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.request.UserSignupRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.mapper.UserMapper;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    @Override
    public UserSignupResponse signup(UserSignupRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXIST);
        }

        User user = UserMapper.toEntity(request, passwordEncoder);

        user = userRepository.save(user);

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
    public User getUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    @Override
    public Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();

        if (principal instanceof Long userId) {
            return userId;
        }

        throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
    }
}