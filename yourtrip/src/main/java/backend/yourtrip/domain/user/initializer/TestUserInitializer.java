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
        String testEmail = "user@example.com";

        User testUser = User.builder()
            .email(testEmail)
            .password(passwordEncoder.encode("Abcd1234!"))
            .nickname("테스트유저")
            .emailVerified(true)
            .deleted(false)
            .build();

        userRepository.save(testUser);
    }

}
