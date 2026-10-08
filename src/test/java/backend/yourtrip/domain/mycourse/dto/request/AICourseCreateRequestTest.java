package backend.yourtrip.domain.mycourse.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 코스 생성 요청의 여행 일수 상한(#178) 경계값.
 *
 * <p>일수는 시작일과 종료일을 모두 포함해 센다(같은 날이면 1일). 서비스가 day 를 만드는 식
 * ({@code ChronoUnit.DAYS.between + 1})과 같아야 상한 5가 실제로 day 5개를 뜻한다.
 */
class AICourseCreateRequestTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 6);
    private static final String TRIP_LENGTH_MESSAGE =
        "AI 코스는 최대 " + AICourseCreateRequest.MAX_TRIP_DAYS + "일까지 생성할 수 있습니다.";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AICourseCreateRequest requestOf(LocalDate start, LocalDate end) {
        return new AICourseCreateRequest("경주", start, end, List.of(KeywordType.WALK));
    }

    private static Set<ConstraintViolation<AICourseCreateRequest>> validate(LocalDate start,
        LocalDate end) {
        return validator.validate(requestOf(start, end));
    }

    @Test
    @DisplayName("당일치기(1일)는 통과한다")
    void sameDayPasses() {
        assertThat(validate(START, START)).isEmpty();
    }

    @Test
    @DisplayName("상한과 같은 일수는 통과한다")
    void maxDaysPasses() {
        LocalDate end = START.plusDays(AICourseCreateRequest.MAX_TRIP_DAYS - 1);

        assertThat(validate(START, end)).isEmpty();
    }

    @Test
    @DisplayName("상한보다 하루 긴 일정은 일수 상한 메시지로 거절된다")
    void maxDaysPlusOneIsRejected() {
        LocalDate end = START.plusDays(AICourseCreateRequest.MAX_TRIP_DAYS);

        assertThat(validate(START, end))
            .extracting(ConstraintViolation::getMessage)
            .containsExactly(TRIP_LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("월을 넘기는 긴 일정도 실제 일수로 센다")
    void crossMonthLongTripIsRejected() {
        // Period.getDays() 로 세면 1/1 ~ 3/5 가 5일로 보이는 결함이 있었다(7cdda90) — 같은 함정을 막는다
        assertThat(validate(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 5)))
            .extracting(ConstraintViolation::getMessage)
            .containsExactly(TRIP_LENGTH_MESSAGE);
    }

    @Test
    @DisplayName("날짜 순서가 뒤집히면 순서 오류만 내고 일수 상한 오류는 겹쳐 내지 않는다")
    void reversedRangeReportsOnlyOrderError() {
        assertThat(validate(START, START.minusDays(10)))
            .extracting(ConstraintViolation::getMessage)
            .containsExactly("startDate는 endDate보다 이후일 수 없습니다.");
    }

    @Test
    @DisplayName("날짜가 비어 있으면 필수값 오류만 내고 일수 상한 검사는 건너뛴다")
    void missingDateSkipsTripLength() {
        assertThat(validate(START, null))
            .extracting(ConstraintViolation::getMessage)
            .containsExactly("여행 기간은 필수 입력 항목입니다.");
    }
}
