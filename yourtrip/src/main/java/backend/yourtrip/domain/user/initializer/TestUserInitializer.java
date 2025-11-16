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
            .build();

        userRepository.save(testUser1);

        //다은
        User testUser2 = User.builder()
            .email("naver@naver.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("이다은")
            .emailVerified(true)
            .build();

        userRepository.save(testUser2);

        //태환
        User testUser3 = User.builder()
            .email("th2194@naver.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("김태환")
            .emailVerified(true)
            .build();

        userRepository.save(testUser3);

        //서구
        User testUser4 = User.builder()
            .email("s2000ten@naver.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("최서구")
            .emailVerified(true)
            .build();

        userRepository.save(testUser4);

        //혜원
        User testUser5 = User.builder()
            .email("jbjokagd@gmail.com")
            .password(passwordEncoder.encode("12345678"))
            .nickname("조혜원")
            .emailVerified(true)
            .build();

        userRepository.save(testUser5);

    }

}
