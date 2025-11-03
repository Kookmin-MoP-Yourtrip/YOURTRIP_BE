package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.CustomException;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public UserServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    @Override
    public UserSignupResponse signup(UserSignupRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new CustomException("이미 가입된 이메일입니다.");
        }

        User user = new User();
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        userRepository.save(user);

        return new UserSignupResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getCreatedAt().toString()
        );
    }

    @Override
    public UserLoginResponse login(UserLoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException("존재하지 않는 이메일입니다."));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException("비밀번호가 일치하지 않습니다.");
        }

        String token = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail());

        return new UserLoginResponse(user.getId(), user.getNickname(), token);
    }
}