package backend.yourtrip.domain.mypage.controller;

import backend.yourtrip.domain.mypage.dto.request.PasswordChangeRequest;
import backend.yourtrip.domain.mypage.dto.request.NicknameChangeRequest;
import backend.yourtrip.domain.mypage.dto.response.ProfileResponse;
import backend.yourtrip.domain.mypage.dto.response.ProfileImageResponse;
import backend.yourtrip.domain.mypage.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mypage/profile")
public class ProfileController {

    private final ProfileService profileService;

    // =========================================================
    // 1. 프로필 조회
    // =========================================================
    @Operation(
        summary = "프로필 조회",
        description = """
            ### 기능 설명
            로그인된 사용자의 프로필 정보를 조회합니다.

            ### 제약조건
            - 로그인이 필요합니다.
            - Soft-delete 처리된 사용자는 조회 불가

            ### 예외상황 / 에러코드
            - `USER_NOT_FOUND(404)`: 존재하지 않는 사용자
            - `ALREADY_DELETED_USER(400)`: 탈퇴한 사용자

            ### 테스트 방법
            1. Swagger에서 **GET /api/mypage/profile** 호출
            2. Authorization 헤더에 Bearer 토큰 포함
            3. 성공 시 이메일/닉네임/프로필 이미지 URL 반환
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "프로필 조회 성공",
            content = @Content(schema = @Schema(implementation = ProfileResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "email": "user@example.com",
                      "nickname": "혼여행러",
                      "profileImageUrl": "https://s3-yourtrip/profile/myprofile.png"
                    }
                    """)))
    })
    @GetMapping
    public ProfileResponse getProfile() {
        return profileService.getProfile();
    }

    // =========================================================
    // 2. 프로필 이미지 변경 (Multipart Form)
    // =========================================================
    @Operation(
        summary = "프로필 이미지 업로드/수정",
        description = """
            ### 기능 설명
            사용자 프로필 이미지를 새 이미지로 업로드하고, S3 URL을 반환합니다.

            ### 요청 형식
            - Content-Type: **multipart/form-data**
            - 필드명: `file`

            ### 제약조건
            - 파일은 반드시 포함되어야 합니다.
            - 파일 크기는 버킷 정책에 따라 제한됨 (예: 5MB 이하 권장)

            ### 예외상황 / 에러코드
            - `INVALID_PROFILE_IMAGE(400)`: 파일 없음
            - `PROFILE_IMAGE_UPLOAD_FAILED(500)`: S3 업로드 실패

            ### 테스트 방법
            1. Swagger -> **PATCH /api/mypage/profile/image**
            2. file 필드에 이미지 업로드
            3. 정상: 업로드된 이미지의 S3 URL 반환
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "업로드 성공",
            content = @Content(schema = @Schema(implementation = ProfileImageResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "profileImageUrl": "https://s3-yourtrip/profile/image_123.png"
                    }
                    """)))
    })
    @PatchMapping(value = "/image", consumes = "multipart/form-data")
    public ProfileImageResponse updateProfileImage(
        @RequestPart("file") MultipartFile file
    ) {
        return profileService.updateProfileImage(file);
    }

    // =========================================================
    // 3. 닉네임 변경
    // =========================================================
    @Operation(
        summary = "닉네임 변경",
        description = """
            ### 기능 설명
            사용자의 닉네임을 새 값으로 수정합니다.

            ### 제약조건
            - 닉네임은 1~20자
            - 공백만 입력 불가
            - 이미 존재하는 닉네임일 경우 사용 불가

            ### 요청 예시
            ```json
            {
              "nickname": "여행조아"
            }
            ```

            ### 예외상황 / 에러코드
            - `INVALID_NICKNAME(400)`
            - `NICKNAME_DUPLICATED(400)`

            ### 테스트 방법
            1. Swagger -> **PATCH /api/mypage/profile/nickname**
            2. 새로운 닉네임 입력 후 실행
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "닉네임 변경 완료"),
        @ApiResponse(responseCode = "400", description = "닉네임 유효성 실패 또는 중복 닉네임")
    })
    @PatchMapping("/nickname")
    public void updateNickname(@RequestBody NicknameChangeRequest request) {
        profileService.updateNickname(request.nickname());
    }

    // =========================================================
    // 4. 비밀번호 변경
    // =========================================================
    @Operation(
        summary = "비밀번호 변경",
        description = """
            ### 기능 설명
            기존 비밀번호 검증 후 새 비밀번호로 변경합니다.

            ### 제약조건
            - 기존 비밀번호가 정확해야 함
            - 새 비밀번호는 최소 8자 이상

            ### 요청 예시
            ```json
            {
              "currentPassword": "oldPassword123!",
              "newPassword": "newPassword123!"
            }
            ```

            ### 예외상황 / 에러코드
            - `PASSWORD_INCORRECT(400)`
            - `NEW_PASSWORD_INVALID(400)`

            ### 테스트 방법
            1. Swagger -> **PATCH /api/mypage/profile/password**
            2. 기존 비밀번호 + 새 비밀번호 입력
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
        @ApiResponse(responseCode = "400", description = "기존 비밀번호 불일치 또는 새 비밀번호 규칙 위반")
    })
    @PatchMapping("/password")
    public void changePassword(@RequestBody PasswordChangeRequest request) {
        profileService.changePassword(request);
    }

    // =========================================================
    // 5. 회원 탈퇴
    // =========================================================
    @Operation(
        summary = "회원 탈퇴",
        description = """
            ### 기능 설명
            로그인된 사용자를 Soft-delete 상태로 변경합니다.

            ### 제약조건
            - 탈퇴 후 재로그인 불가
            - DB에서 실제 삭제되지 않고 deleted 플래그만 true 처리

            ### 예외상황 / 에러코드
            - `ALREADY_DELETED_USER(400)`: 이미 탈퇴한 상태

            ### 테스트 방법
            1. Swagger -> **DELETE /api/mypage/profile**
            2. Authorization 토큰 포함 후 실행
            3. 성공 시 200 OK(본문 없음)
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "탈퇴 성공"),
        @ApiResponse(responseCode = "400", description = "이미 탈퇴한 계정")
    })
    @DeleteMapping
    @ResponseStatus(HttpStatus.OK)
    public void deleteUser() {
        profileService.deleteUser();
    }

    @GetMapping("/nickname/check")
    public void checkNickname(@RequestParam String nickname) {
        profileService.checkNickname(nickname);
    }
}