package backend.yourtrip.domain.uploadcourse.entity;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "course_keyword_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_course_id")
    private UploadCourse uploadCourse;

    @Enumerated(EnumType.STRING)
    private KeywordType keywordType;

    public CourseKeyword(UploadCourse uploadCourse, KeywordType keywordType) {
        this.uploadCourse = uploadCourse;
        this.keywordType = keywordType;

    }

}
