package backend.yourtrip.domain.uploadcourse.validator;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;

public class ValidKeywordTypeValidator implements
    ConstraintValidator<ValidKeywordType, List<KeywordType>> {

    @Override
    public boolean isValid(List<KeywordType> keywords, ConstraintValidatorContext context) {
        if (keywords == null) {
            return true; // @NotNull이 따로 있으므로 여기선 통과
        }

        // Enum 값 검증 (Jackson이 바인딩 실패하면 ControllerAdvice에서 따로 걸림)
        for (KeywordType keyword : keywords) {
            if (keyword == null) {
                return false;
            }
        }
        return true;
    }
}