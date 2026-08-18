package backend.yourtrip.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * [측정용 임시 스위치 — #88] Lettuce의 shareNativeConnection을 프로퍼티로 끌 수 있게 한다.
 *
 * 기본값(true)은 앱↔Redis TCP 연결 1개를 모든 RedisTemplate/RedisCacheManager 작업이 공유하는
 * 운영 동작 그대로다. false면 모든 getConnection()이 풀(spring.data.redis.lettuce.pool)에서
 * 전용 커넥션을 빌려 쓰므로 채널이 최대 max-active개로 늘어나 Lettuce I/O 스레드(2 vCPU에서
 * DefaultClientResources 기본값 2개)에 분산된다 — "단일 I/O 스레드 런큐 대기" 병목의 대안으로
 * Tomcat maxThreads 축소와 같은 배치에서 비교하기 위한 arm이다.
 *
 * Spring Boot에는 이 값을 바꾸는 프로퍼티가 없고, LettuceConnectionFactory는 afterPropertiesSet
 * 이전에만 setShareNativeConnection을 허용하므로 postProcessBeforeInitialization에서 건드린다.
 * BeanPostProcessor는 컨테이너 초기에 만들어져 @Value 주입 순서를 타기 애매하므로 Environment를
 * 생성자로 받아 직접 읽는다.
 *
 * 측정이 끝나면 제거한다(d144126의 벤치마크 토글과 같은 관례).
 */
@Slf4j
@Component
public class LettuceShareNativeConnectionCustomizer implements BeanPostProcessor {

    private final boolean shareNativeConnection;

    public LettuceShareNativeConnectionCustomizer(Environment environment) {
        this.shareNativeConnection = environment.getProperty(
                "yourtrip.redis.share-native-connection", Boolean.class, true);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof LettuceConnectionFactory factory && !shareNativeConnection) {
            factory.setShareNativeConnection(false);
            log.warn("[benchmark] yourtrip.redis.share-native-connection=false — Lettuce 공유 커넥션을 끄고 "
                    + "모든 명령이 풀에서 전용 커넥션을 빌린다. 측정용 비기본 설정이다.");
        }
        return bean;
    }
}
