package backend.yourtrip.domain.mypage.controller;

import backend.yourtrip.domain.mypage.dto.request.NicknameUpdateRequest;
import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.mypage.service.MyPageProfileService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mypage/profile")
@RequiredArgsConstructor
public class MyPageProfileController {

    private final MyPageProfileService myPageProfileService;

    // =========================================================
    // 1. 프로필 이미지 업로드
    // =========================================================
    @Operation(
        summary = "프로필 이미지 업로드",
        description = """
        ### 기능 설명
        - 로그인한 사용자의 **프로필 이미지를 S3에 업로드**합니다.
        - 업로드된 이미지의 최종 URL을 유저 엔티티에 저장하고, 응답으로 반환합니다.

        ### 제약조건
        - **로그인 필수**: Authorization 헤더에 `Bearer {accessToken}` 필요
        - Request 형식:
          - `Content-Type: multipart/form-data`
          - 필드 이름: `file`
          - 허용 포맷: `image/jpeg`, `image/png`
        - 파일 크기: 서버/인프라에서 허용하는 최대 용량(예: 5MB) 이내 권장

        ### 예외상황 / 에러코드
        - `INVALID_PROFILE_IMAGE (400)`
          - 파일이 전송되지 않았거나, 비어 있는 경우
        - `USER_NOT_FOUND (404)`
          - 정상적인 인증 정보가 없거나, 사용자 정보가 존재하지 않는 경우
        - `PROFILE_IMAGE_UPLOAD_FAILED (500)`
          - S3 업로드 중 예기치 못한 오류 발생

        ### 정상 응답 예시
        ```json
        {
          "profileImageUrl": "https://yourtrip.s3.ap-northeast-2.amazonaws.com/profile/abc123.png"
        }
        ```

        ### 에러 응답 예시
        - 잘못된 파일 입력(미첨부 등)
        ```json
        {
          "timestamp": "2025-11-20T10:00:00",
          "code": "INVALID_PROFILE_IMAGE",
          "message": "업로드할 프로필 이미지가 필요합니다."
        }
        ```

        - S3 업로드 실패
        ```json
        {
          "timestamp": "2025-11-20T10:01:00",
          "code": "PROFILE_IMAGE_UPLOAD_FAILED",
          "message": "프로필 이미지 업로드에 실패했습니다."
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "프로필 이미지 업로드 성공",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ProfileImageResponse.class),
                examples = @ExampleObject(value = """
                {
                  "profileImageUrl": "https://yourtrip.s3.ap-northeast-2.amazonaws.com/profile/abc123.png"
                }
                """)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "잘못된 파일 입력(미첨부/형식 오류 등)",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T10:00:00",
                  "code": "INVALID_PROFILE_IMAGE",
                  "message": "업로드할 프로필 이미지가 필요합니다."
                }
                """)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 정보 없음(비로그인 등)",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T10:00:30",
                  "code": "USER_NOT_FOUND",
                  "message": "사용자를 찾을 수 없습니다."
                }
                """)
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "S3 업로드 실패",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T10:01:00",
                  "code": "PROFILE_IMAGE_UPLOAD_FAILED",
                  "message": "프로필 이미지 업로드에 실패했습니다."
                }
                """)
            )
        )
    })
    @PostMapping("/image")
    public ProfileImageResponse uploadImage(
        @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        return myPageProfileService.updateProfileImage(file);
    }

    // =========================================================
    // 2. 닉네임 변경
    // =========================================================
    @Operation(
        summary = "닉네임 변경",
        description = """
        ### 기능 설명
        - 현재 로그인한 사용자의 **닉네임을 변경**합니다.

        ### 제약조건
        - 로그인 필수 (Bearer Token)
        - 닉네임 제약:
          - 공백 불가, 최소 1자 이상
          - 최대 20자
          - 이미 다른 사용자가 사용 중인 닉네임은 불가

        ### 예외상황 / 에러코드
        - `INVALID_NICKNAME (400)`
          - 닉네임이 비어있거나, 길이(1~20자)를 벗어난 경우
        - `NICKNAME_DUPLICATED (400)`
          - 이미 다른 사용자가 사용 중인 닉네임인 경우
        - `USER_NOT_FOUND (404)`
          - 로그인 정보가 없거나, 사용자가 존재하지 않는 경우

        ### 요청 예시
        ```json
        {
          "nickname": "여행고래"
        }
        ```

        ### 정상 응답
        - 상태 코드만 200 OK 반환, **본문 없음**

        ### 에러 응답 예시
        - 닉네임 형식 오류
        ```json
        {
          "timestamp": "2025-11-20T11:00:00",
          "code": "INVALID_NICKNAME",
          "message": "닉네임 형식이 올바르지 않습니다."
        }
        ```

        - 닉네임 중복
        ```json
        {
          "timestamp": "2025-11-20T11:01:00",
          "code": "NICKNAME_DUPLICATED",
          "message": "이미 사용 중인 닉네임입니다."
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "닉네임 변경 성공(본문 없음)"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "닉네임 형식 오류 또는 중복",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "형식 오류",
                        value = """
                        {
                          "timestamp": "2025-11-20T11:00:00",
                          "code": "INVALID_NICKNAME",
                          "message": "닉네임 형식이 올바르지 않습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "중복 닉네임",
                        value = """
                        {
                          "timestamp": "2025-11-20T11:01:00",
                          "code": "NICKNAME_DUPLICATED",
                          "message": "이미 사용 중인 닉네임입니다."
                        }
                        """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T11:02:00",
                  "code": "USER_NOT_FOUND",
                  "message": "사용자를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    @PutMapping("/nickname")
    public void updateNickname(@Valid @RequestBody NicknameUpdateRequest request) {
        myPageProfileService.updateNickname(request.nickname());
    }

    // =========================================================
    // 3. 비밀번호 변경
    // =========================================================
    @Operation(
        summary = "비밀번호 변경",
        description = """
        ### 기능 설명
        - 로그인한 사용자가 **현재 비밀번호를 검증한 뒤, 새 비밀번호로 변경**합니다.

        ### 제약조건
        - 로그인 필수(Bearer Token)
        - 요청 필드:
          - `currentPassword` : 현재 비밀번호 (필수)
          - `newPassword` : 새 비밀번호 (필수, 최소 8자 이상)
        - 비밀번호 정책:
          - 최소 8자 이상
          - 공백 불가
          - 영문/숫자/특수문자 조합 권장

        ### 예외상황 / 에러코드
        - `PASSWORD_INCORRECT (400)`
          - 현재 비밀번호가 DB에 저장된 비밀번호와 일치하지 않는 경우
        - `NEW_PASSWORD_INVALID (400)`
          - 새 비밀번호가 8자 미만이거나 정책을 위반하는 경우
        - `USER_NOT_FOUND (404)`
          - 로그인 정보가 없거나, 사용자 미존재

        ### 요청 예시
        ```json
        {
          "currentPassword": "OldPw1234!",
          "newPassword": "NewPw9876!"
        }
        ```

        ### 정상 응답
        - 상태 코드 200 OK (본문 없음)

        ### 에러 응답 예시
        - 현재 비밀번호 불일치
        ```json
        {
          "timestamp": "2025-11-20T12:00:00",
          "code": "PASSWORD_INCORRECT",
          "message": "기존 비밀번호가 일치하지 않습니다."
        }
        ```

        - 새 비밀번호 형식 오류
        ```json
        {
          "timestamp": "2025-11-20T12:01:00",
          "code": "NEW_PASSWORD_INVALID",
          "message": "새 비밀번호 형식이 올바르지 않습니다."
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "비밀번호 변경 성공(본문 없음)"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "기존 비밀번호 불일치 또는 새 비밀번호 형식 오류",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "기존 비밀번호 불일치",
                        value = """
                        {
                          "timestamp": "2025-11-20T12:00:00",
                          "code": "PASSWORD_INCORRECT",
                          "message": "기존 비밀번호가 일치하지 않습니다."
                        }
                        """
                    ),
                    @ExampleObject(
                        name = "새 비밀번호 형식 오류",
                        value = """
                        {
                          "timestamp": "2025-11-20T12:01:00",
                          "code": "NEW_PASSWORD_INVALID",
                          "message": "새 비밀번호 형식이 올바르지 않습니다."
                        }
                        """
                    )
                }
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T12:02:00",
                  "code": "USER_NOT_FOUND",
                  "message": "사용자를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    @PutMapping("/password")
    public void changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        myPageProfileService.changePassword(request);
    }

    // =========================================================
    // 4. 회원 탈퇴 (Soft Delete)
    // =========================================================
    @Operation(
        summary = "회원 탈퇴 (Soft Delete)",
        description = """
        ### 기능 설명
        - 현재 로그인한 사용자를 **소프트 삭제(Soft Delete)** 처리합니다.
        - 실제로 DB 레코드를 삭제하지 않고, `deleted = true`로 변경합니다.

        ### 제약조건
        - 로그인 필수(Bearer Token)
        - 이미 탈퇴 처리된 사용자는 다시 탈퇴할 수 없습니다.

        ### 예외상황 / 에러코드
        - `ALREADY_DELETED_USER (400)`
          - deleted = true 인 사용자가 다시 탈퇴 요청한 경우
        - `USER_NOT_FOUND (404)`
          - 로그인 정보가 없거나, 사용자 정보가 존재하지 않는 경우

        ### 정상 응답
        - 상태 코드 200 OK (본문 없음)

        ### 에러 응답 예시
        - 이미 탈퇴된 사용자
        ```json
        {
          "timestamp": "2025-11-20T13:00:00",
          "code": "ALREADY_DELETED_USER",
          "message": "이미 탈퇴된 사용자입니다."
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "회원 탈퇴(Soft Delete) 성공(본문 없음)"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "이미 탈퇴된 사용자",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T13:00:00",
                  "code": "ALREADY_DELETED_USER",
                  "message": "이미 탈퇴된 사용자입니다."
                }
                """)
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "사용자 없음",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-20T13:01:00",
                  "code": "USER_NOT_FOUND",
                  "message": "사용자를 찾을 수 없습니다."
                }
                """)
            )
        )
    })
    @DeleteMapping
    public void deleteUser() {
        myPageProfileService.deleteUser();
    }
}