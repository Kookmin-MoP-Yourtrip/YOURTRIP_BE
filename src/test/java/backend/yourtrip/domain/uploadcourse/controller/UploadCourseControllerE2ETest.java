package backend.yourtrip.domain.uploadcourse.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.uploadcourse.dto.request.DayScheduleUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadCourseControllerE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UploadCourseService uploadCourseService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserRepository userRepository;

    @Test
    @DisplayName("GET /api/upload-courses/{uploadCourseId} - 요청의 IP/User-Agent로 계산한 viewerKey를 getDetail에 전달하고 200 OK를 반환한다")
    void getUploadCourseDetail_Success() throws Exception {
        // given
        Long uploadCourseId = 1L;
        String viewerKey = "a1b2c3";

        UploadCourseDetailResponse expectedResponse = UploadCourseDetailResponse.builder()
            .uploadCourseId(uploadCourseId)
            .title("경주 인생샷 코스")
            .introduction("황리단길과 첨성대 야경")
            .location("경주")
            .thumbnailImageUrl("http://s3.com/thumbnail.png")
            .startDate(LocalDate.of(2025, 3, 1))
            .endDate(LocalDate.of(2025, 3, 1))
            .forkCount(5)
            .keywords(List.of("뚜벅이", "맛집탐방"))
            .daySchedules(List.of())
            .build();

        given(uploadCourseService.resolveViewerKey(anyString(), anyString())).willReturn(viewerKey);
        given(uploadCourseService.getDetail(uploadCourseId, viewerKey)).willReturn(expectedResponse);

        // when & then
        mockMvc.perform(get("/api/upload-courses/{uploadCourseId}", uploadCourseId)
                .header("User-Agent", "Mozilla/5.0"))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uploadCourseId").value(uploadCourseId))
            .andExpect(jsonPath("$.title").value("경주 인생샷 코스"));
    }

    @Test
    @DisplayName("PUT /api/upload-courses/{uploadCourseId} - 로그인한 사용자가 정상적인 정보로 수정 시 200 OK와 수정된 코스 상세 응답을 반환한다")
    @WithMockUser
    void updateUploadCourse_Success() throws Exception {
        // given
        Long uploadCourseId = 1L;

        String requestJson = """
            {
              "title": "경주 인생샷 코스 (수정본)",
              "introduction": "황리단길과 첨성대 야경 수정 완료",
              "location": "경주",
              "startDate": "2025-03-01",
              "endDate": "2025-03-01",
              "keywords": ["WALK", "FOOD"],
              "daySchedules": [
                {
                  "dayScheduleId": 100,
                  "day": 1,
                  "places": [
                    {
                      "placeId": 200,
                      "placeName": "황리단길 카페 거리",
                      "startTime": "10:30:00",
                      "memo": "카페 골목 산책",
                      "latitude": 35.8375,
                      "longitude": 129.2123,
                      "placeUrl": "http://place.map.kakao.com/26338954",
                      "placeLocation": "경북 경주시 포석로 인근"
                    }
                  ]
                }
              ]
            }
            """;

        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "request.json",
            MediaType.APPLICATION_JSON_VALUE,
            requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        MockMultipartFile thumbnailPart = new MockMultipartFile(
            "thumbnailImage",
            "thumbnail.png",
            MediaType.IMAGE_PNG_VALUE,
            "fake thumbnail content".getBytes()
        );

        UploadCourseDetailResponse expectedResponse = UploadCourseDetailResponse.builder()
            .uploadCourseId(uploadCourseId)
            .title("경주 인생샷 코스 (수정본)")
            .introduction("황리단길과 첨성대 야경 수정 완료")
            .location("경주")
            .thumbnailImageUrl("http://s3.com/thumbnail.png")
            .startDate(LocalDate.of(2025, 3, 1))
            .endDate(LocalDate.of(2025, 3, 1))
            .forkCount(5)
            .keywords(List.of("뚜벅이", "맛집탐방"))
            .daySchedules(List.of(
                new DayScheduleResponse(100L, 1, List.of())
            ))
            .build();

        given(uploadCourseService.updateUploadCourse(eq(uploadCourseId), any(), nullable(org.springframework.web.multipart.MultipartFile.class), nullable(List.class)))
            .willReturn(expectedResponse);

        // when & then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/upload-courses/{uploadCourseId}", uploadCourseId)
                .file(requestPart)
                .file(thumbnailPart)
                .contentType(MediaType.MULTIPART_FORM_DATA_VALUE))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uploadCourseId").value(uploadCourseId))
            .andExpect(jsonPath("$.title").value("경주 인생샷 코스 (수정본)"))
            .andExpect(jsonPath("$.introduction").value("황리단길과 첨성대 야경 수정 완료"))
            .andExpect(jsonPath("$.location").value("경주"))
            .andExpect(jsonPath("$.thumbnailImageUrl").value("http://s3.com/thumbnail.png"));
    }

    @Test
    @DisplayName("PUT /api/upload-courses/{uploadCourseId} - 비인증 사용자가 요청 시 401/403 응답을 반환한다")
    void updateUploadCourse_Unauthenticated_ReturnsForbiddenOrUnauthorized() throws Exception {
        // given
        Long uploadCourseId = 1L;

        String requestJson = """
            {
              "title": "제목",
              "startDate": "2025-03-01",
              "endDate": "2025-03-01",
              "keywords": [],
              "daySchedules": []
            }
            """;

        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "request.json",
            MediaType.APPLICATION_JSON_VALUE,
            requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        // when & then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/upload-courses/{uploadCourseId}", uploadCourseId)
                .file(requestPart)
                .contentType(MediaType.MULTIPART_FORM_DATA_VALUE))
            .andDo(print())
            .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("PUT /api/upload-courses/{uploadCourseId} - 제목이 비어있는 검증 실패 요청 시 400 Bad Request를 반환한다")
    @WithMockUser
    void updateUploadCourse_ValidationFailed_ReturnsBadRequest() throws Exception {
        // given
        Long uploadCourseId = 1L;

        String requestJson = """
            {
              "title": "",
              "startDate": "2025-03-01",
              "endDate": "2025-03-01",
              "keywords": [],
              "daySchedules": []
            }
            """;

        MockMultipartFile requestPart = new MockMultipartFile(
            "request",
            "request.json",
            MediaType.APPLICATION_JSON_VALUE,
            requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );

        // when & then
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/upload-courses/{uploadCourseId}", uploadCourseId)
                .file(requestPart)
                .contentType(MediaType.MULTIPART_FORM_DATA_VALUE))
            .andDo(print())
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/upload-courses/{uploadCourseId} - 로그인한 사용자가 삭제 요청 시 204 No Content를 반환하고 서비스에 위임한다")
    @WithMockUser
    void deleteUploadCourse_Success() throws Exception {
        // given
        Long uploadCourseId = 1L;

        // when & then
        mockMvc.perform(delete("/api/upload-courses/{uploadCourseId}", uploadCourseId))
            .andDo(print())
            .andExpect(status().isNoContent());

        verify(uploadCourseService).deleteUploadCourse(uploadCourseId);
    }

    @Test
    @DisplayName("DELETE /api/upload-courses/{uploadCourseId} - 비인증 사용자가 요청 시 401/403 응답을 반환한다")
    void deleteUploadCourse_Unauthenticated_ReturnsForbiddenOrUnauthorized() throws Exception {
        // given
        Long uploadCourseId = 1L;

        // when & then
        mockMvc.perform(delete("/api/upload-courses/{uploadCourseId}", uploadCourseId))
            .andDo(print())
            .andExpect(status().is4xxClientError());
    }
}
