package backend.yourtrip.global.tour.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link TourApiConfig#normalizeServiceKey} 단위 테스트 (ROADMAP 4-7 · 4-6).
 *
 * <p>이 함수 하나가 <b>"어느 형태의 키를 발급받았는가"를 장애 원인에서 지운다.</b> 실호출에서
 * 이중 인코딩된 키는 403 {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}로 돌아왔고, 그건 로그만
 * 봐서는 "키가 잘못됐다"로 읽혀 원인을 찾기 어려운 실패다.
 */
@DisplayName("TourApiConfig — serviceKey 정규화 (ROADMAP 4-7)")
class TourApiConfigTest {

    @Nested
    @DisplayName("Encoding 키 — 손대지 않는다")
    class AlreadyEncoded {

        @Test
        @DisplayName("퍼센트 인코딩된 키는 그대로 둔다 — 한 번 더 인코딩하면 인증이 깨진다")
        void keepsEncodedKeyAsIs() {
            String encoded = "abcDEF123%2BxyZ%2F987%3D%3D";

            assertThat(TourApiConfig.normalizeServiceKey(encoded)).isEqualTo(encoded);
        }

        @Test
        @DisplayName("앞뒤 공백은 걷어낸다 — .env 편집에서 흔히 붙는다")
        void trimsSurroundingWhitespace() {
            assertThat(TourApiConfig.normalizeServiceKey("  abc%2Bdef  "))
                .isEqualTo("abc%2Bdef");
        }
    }

    @Nested
    @DisplayName("Decoding 키 — 한 번 인코딩한다")
    class PlainKey {

        @Test
        @DisplayName("평문 키의 +, /, =는 인코딩된다")
        void encodesReservedCharacters() {
            assertThat(TourApiConfig.normalizeServiceKey("abc+def/ghi=="))
                .isEqualTo("abc%2Bdef%2Fghi%3D%3D");
        }

        @Test
        @DisplayName("+가 공백으로 해석되는 사고를 막는다 — 디코딩 쪽으로 맞췄다면 이 글자가 조용히 바뀐다")
        void protectsPlusSign() {
            String normalized = TourApiConfig.normalizeServiceKey("abc+def");

            assertThat(normalized).doesNotContain("+");
            assertThat(URLDecoder.decode(normalized, StandardCharsets.UTF_8))
                .as("서버가 디코딩하면 원래 키로 돌아와야 한다")
                .isEqualTo("abc+def");
        }

        @Test
        @DisplayName("특수문자가 없는 키는 그대로다")
        void leavesAlphanumericKeyUnchanged() {
            assertThat(TourApiConfig.normalizeServiceKey("abcDEF123")).isEqualTo("abcDEF123");
        }
    }

    @Nested
    @DisplayName("두 형태 중 무엇을 넣어도 같은 결과다")
    class Idempotent {

        @Test
        @DisplayName("같은 키의 Encoding 형태와 Decoding 형태가 같은 값으로 수렴한다")
        void bothIssuanceFormsConverge() {
            assertThat(TourApiConfig.normalizeServiceKey("abc+def/ghi=="))
                .isEqualTo(TourApiConfig.normalizeServiceKey("abc%2Bdef%2Fghi%3D%3D"));
        }

        @Test
        @DisplayName("두 번 정규화해도 값이 변하지 않는다")
        void isIdempotent() {
            String once = TourApiConfig.normalizeServiceKey("abc+def");

            assertThat(TourApiConfig.normalizeServiceKey(once)).isEqualTo(once);
        }
    }

    @Nested
    @DisplayName("키가 없어도 기동은 성공해야 한다")
    class MissingKey {

        @Test
        @DisplayName("null·빈 값은 빈 문자열이다 — 후보 공급은 fail-open이라 기동을 막지 않는다")
        void returnsEmptyForMissingKey() {
            assertThat(TourApiConfig.normalizeServiceKey(null)).isEmpty();
            assertThat(TourApiConfig.normalizeServiceKey("")).isEmpty();
            assertThat(TourApiConfig.normalizeServiceKey("   ")).isEmpty();
        }
    }
}
