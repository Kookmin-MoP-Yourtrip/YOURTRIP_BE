package backend.yourtrip.global.gemini.service;

import backend.yourtrip.domain.mycourse.entity.myCourse.enums.MyCourseType;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import com.google.genai.Client;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client geminiClient;

    public String generateTravelCourse(String location, int days, List<KeywordType> keywords){
        String prompt= """
            
            """
    }

}
