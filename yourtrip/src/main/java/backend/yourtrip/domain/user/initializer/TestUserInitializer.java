package backend.yourtrip.domain.user.initializer;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
@RequiredArgsConstructor
public class TestUserInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        //지은
        User testUser1 = User.builder()
            .email("jeeun03@gmail.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("남지은")
            .emailVerified(true)
            .deleted(false)
            .build();

        userRepository.save(testUser1);

        //다은
        User testUser2 = User.builder()
            .email("naver@naver.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("남지은")
            .emailVerified(true)
            .deleted(false)
            .build();

        userRepository.save(testUser2);
    }

}
