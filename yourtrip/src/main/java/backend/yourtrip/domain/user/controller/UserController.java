package backend.yourtrip.domain.user.controller;
import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.service.dto.request.*;
import backend.yourtrip.domain.user.service.dto.response.*;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.domain.user.service.KakaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * UserController
 *
 * 회원 관련 API (이메일 회원가입 단계 / 로그인 / 토큰 재발급)
 * - 피그마 플로우: 이메일 입력 -> 인증번호 입력 -> 비밀번호 설정 -> 프로필 등록(최종 가입)
 * - 각 엔드포인트에 제약조건, 에러처리, 테스트 방법을 Swagger 문서에 상세 기재
 */

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final KakaoService kakaoService;

    // =========================================================
    // 1. 이메일 인증번호 발송
    // =========================================================
    @Operation(
        summary = "이메일 인증번호 발송",
        description = """
        ### 제약조건
        - 이메일은 **이미 가입된 이메일에는 발송 불가**합니다.
        - 동일 이메일로 재요청 시 **가장 마지막으로 발급된 인증번호**만 유효합니다.
        - 인증번호는 6자리 숫자, 유효시간 기본 **5분**
        ### 예외상황 / 에러코드
        - `EMAIL_ALREADY_EXIST(400)`: 이미 가입된 이메일로 요청.
        - `INVALID_REQUEST_FIELD(400)`: 이메일 형식 불일치 또는 빈 값.
        ### 테스트 방법
        1. Swagger에서 **POST** `/api/users/email/send` 실행
        2. 요청 예시:
        ```json
        {
          "email": "newuser@example.com"
        }
        ```
        3. 정상: **200 OK**(본문 없음). 콘솔/메일 수신함에서 인증번호 확인(개발 단계는 서버 로그로 노출).
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "인증번호 발송 성공(본문 없음)"),
        @ApiResponse(responseCode = "400", description = "중복 이메일/형식 오류",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-11T10:00:00",
                  "code": "EMAIL_ALREADY_EXIST",
                  "message": "이미 가입된 이메일입니다."
                }
                """)))
    })
    @PostMapping("/email/send")
    @ResponseStatus(HttpStatus.OK)
    public void sendVerificationCode(@RequestBody EmailSendRequest request) {
        userService.sendVerificationCode(request.email());
    }

    // =========================================================
    // 2. 인증번호 검증
    // =========================================================
    @Operation(
        summary = "이메일 인증번호 검증",
        description = """
        ### 제약조건
        - 요청 본문에 **email**과 **code(6자리)** 모두 필수입니다.
        - 올바른 코드라도 유효시간이 지나면 실패합니다.
        ### 예외상황 / 에러코드
        - `INVALID_VERIFICATION_CODE(400)`: 코드 불일치.
        - `VERIFICATION_CODE_EXPIRED(400)`: 코드 만료 또는 미발급.
        - `INVALID_REQUEST_FIELD(400)`: 필드 누락/형식 오류.
        ### 테스트 방법
        1. 이메일로 수신한 인증번호를 입력해 **POST** `/api/users/email/verify` 요청
        2. 요청 예시:
        ```json
        { "email": "newuser@example.com", "code": "123456" }
        ```
        3. 정상: **200 OK**(본문 없음). 이후 단계(비밀번호 설정) 진행 가능.
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "인증 성공(본문 없음)"),
        @ApiResponse(responseCode = "400", description = "코드 오류/만료/필드 오류",
            content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "코드 불일치", value = """
                {
                  "timestamp": "2025-11-11T10:03:00",
                  "code": "INVALID_VERIFICATION_CODE",
                  "message": "인증번호가 올바르지 않습니다."
                }
                """),
                @ExampleObject(name = "코드 만료", value = """
                {
                  "timestamp": "2025-11-11T10:04:00",
                  "code": "VERIFICATION_CODE_EXPIRED",
                  "message": "인증번호가 만료되었습니다."
                }
                """)
            }))
    })
    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.OK)
    public void verifyCode(@RequestBody EmailVerifyRequest request) {
        userService.verifyCode(request.email(), request.code());
    }

    // =========================================================
    // 3. 비밀번호 설정
    // =========================================================
    @Operation(
        summary = "비밀번호 설정",
        description = """
        ### 제약조건
        - **이메일 인증(2단계)이 완료된 이메일**만 비밀번호를 설정할 수 있습니다.
        - 비밀번호 정책(문서 기준):
          - 최소 **8자 이상**
          - 공백 불가
          - 영문/숫자/특수문자 조합 권장
        ### 예외상황 / 에러코드
        - `EMAIL_NOT_VERIFIED(400)`: 이메일 인증 미완료 상태에서 요청.
        - `INVALID_REQUEST_FIELD(400)`: 비밀번호 형식 위반/필드 누락.
        ### 테스트 방법
        1. **POST** `/api/users/password`
        2. 요청 예시:
        ```json
        { "email": "newuser@example.com", "password": "Abcd1234!" }
        ```
        3. 정상: **200 OK**(본문 없음). 이후 프로필 등록 단계 진행.
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "비밀번호 설정 성공(본문 없음)"),
        @ApiResponse(responseCode = "400", description = "미인증/형식 오류",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-11T10:05:00",
                  "code": "EMAIL_NOT_VERIFIED",
                  "message": "이메일 인증이 완료되지 않았습니다."
                }
                """)))
    })
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.OK)
    public void setPassword(@RequestBody PasswordSetRequest request) {
        userService.setPassword(request.email(), request.password());
    }

    // =========================================================
    // 4. 프로필 등록 (최종 회원가입 완료)
    // =========================================================
    @Operation(
        summary = "프로필 등록(최종 회원가입 완료)",
        description = """
        ### 제약조건
        - 닉네임: **1~20자**
        - 프로필 이미지는 선택값(`profileImageUrl`), 미지정 시 서버 기본값 사용 가능(정책에 따름).
        - 비밀번호가 **사전 설정(3단계)** 되어 있어야 최종 회원 생성이 됩니다.
        ### 예외상황 / 에러코드
        - `EMAIL_NOT_VERIFIED(400)`: 이메일 인증 미완료.
        - `INVALID_REQUEST_FIELD(400)`: 닉네임 규칙 위반/필드 누락.
        - `USER_NOT_FOUND(404)`: 내부 임시 정보 미존재 등으로 가입 완료 불가.
        - `EMAIL_ALREADY_EXIST(400)`: 경합 상황에서 동일 이메일이 이미 가입 완료된 경우.
        ### 테스트 방법
        1. **POST** `/api/users/profile`
        2. 요청 예시:
        ```json
        {
          "email": "newuser@example.com",
          "nickname": "여행러버",
          "profileImageUrl": "https://cdn.example.com/profile.png"
        }
        ```
        3. 정상: **201 Created** + 가입 사용자 정보 반환.
        4. 응답 예시:
        ```json
        {
          "userId": 1,
          "email": "newuser@example.com",
          "nickname": "여행러버",
          "createdAt": "2025-11-11T10:06:00"
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "가입 완료",
            content = @Content(schema = @Schema(implementation = UserSignupResponse.class),
                examples = @ExampleObject(value = """
                {
                  "userId": 1,
                  "email": "newuser@example.com",
                  "nickname": "여행러버",
                  "createdAt": "2025-11-11T10:06:00"
                }
                """))),
        @ApiResponse(responseCode = "400", description = "미인증/필드 오류/중복",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-11T10:06:30",
                  "code": "EMAIL_NOT_VERIFIED",
                  "message": "이메일 인증이 완료되지 않았습니다."
                }
                """))),
        @ApiResponse(responseCode = "404", description = "임시 가입 정보 없음",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-11T10:06:40",
                  "code": "USER_NOT_FOUND",
                  "message": "사용자를 찾을 수 없습니다."
                }
                """)))
    })
    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSignupResponse completeSignup(@RequestBody ProfileCreateRequest request) {
        return userService.completeSignup(request);
    }

    // =========================================================
    // 5. 로그인
    // =========================================================
    @Operation(
        summary = "이메일 로그인",
        description = """
        ### 제약조건
        - **email**/**password** 모두 필수입니다.
        ### 예외상황 / 에러코드
        - `EMAIL_NOT_FOUND(400)`: 가입되지 않은 이메일.
        - `NOT_MATCH_PASSWORD(400)`: 비밀번호 불일치.
        ### 테스트 방법
        1. **POST** `/api/users/login`
        2. 요청 예시:
        ```json
        { "email": "newuser@example.com", "password": "Abcd1234!" }
        ```
        3. 정상: **200 OK** + Access Token 반환.
        4. 응답 예시:
        ```json
        {
          "userId": 1,
          "nickname": "여행러버",
          "accessToken": "eyJhbGciOi..."
        }
        ```
        - Swagger의 **Authorize** 버튼에 `Bearer {accessToken}` 입력 후 인증이 필요한 API 호출 가능.
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공",
            content = @Content(schema = @Schema(implementation = UserLoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "이메일/비밀번호 오류",
            content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "이메일 없음", value = """
                {
                  "timestamp": "2025-11-11T10:08:00",
                  "code": "EMAIL_NOT_FOUND",
                  "message": "존재하지 않는 이메일입니다."
                }
                """),
                @ExampleObject(name = "비밀번호 불일치", value = """
                {
                  "timestamp": "2025-11-11T10:08:10",
                  "code": "NOT_MATCH_PASSWORD",
                  "message": "비밀번호가 일치하지 않습니다."
                }
                """)
            }))
    })
    @PostMapping("/login")
    public UserLoginResponse login(@RequestBody UserLoginRequest request) {
        return userService.login(request);
    }
    // =========================================================
    // 6. Access Token 재발급
    // =========================================================
    @Operation(
        summary = "Access Token 재발급",
        description = """
        ### 제약조건
        - **Authorization 헤더**로 **Refresh Token(원문 그대로)** 을 전달합니다.
          - 예) `Authorization: {refreshToken}`
          - (주의) `Bearer ` 접두어 **붙이지 않습니다**. 서버 구현이 원문 토큰을 기대합니다.
        ### 예외상황 / 에러코드
        - `INVALID_REFRESH_TOKEN(400)`: 위변조/만료 등으로 토큰이 유효하지 않음.
        - `NOT_MATCH_REFRESH_TOKEN(400)`: 서버에 저장된 Refresh Token과 불일치.
        ### 테스트 방법
        1. 로그인 성공 시 응답 본문에 포함된 **refreshToken**(서버 내부 저장)을 사용.
           - 현재 API는 새 Access Token만 응답합니다.
        2. **POST** `/api/users/refresh` 에 `Authorization` 헤더로 Refresh Token 전달.
        3. 정상: **200 OK** + 새 Access Token 반환.
        4. 응답 예시:
        ```json
        {
          "userId": 1,
          "nickname": "여행러버",
          "accessToken": "eyJhbGciOi...new"
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "재발급 성공",
            content = @Content(schema = @Schema(implementation = UserLoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "리프레시 토큰 오류",
            content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "유효하지 않음", value = """
                {
                  "timestamp": "2025-11-11T10:09:00",
                  "code": "INVALID_REFRESH_TOKEN",
                  "message": "유효하지 않은 리프레시 토큰입니다."
                }
                """),
                @ExampleObject(name = "서버 저장값과 불일치", value = """
                {
                  "timestamp": "2025-11-11T10:09:10",
                  "code": "NOT_MATCH_REFRESH_TOKEN",
                  "message": "리프레시 토큰이 일치하지 않습니다."
                }
                """)
            }))
    })
    @PostMapping("/refresh")
    public UserLoginResponse refresh(@RequestHeader("Authorization") String refreshToken) {
        return userService.refresh(refreshToken);
    }

    // =========================================================
    // [카카오 로그인]
    // =========================================================

    @Operation(
        summary = "카카오 OAuth2 콜백 (로그인 진입점)",
        description = """
        카카오에서 리다이렉트되는 **로그인 콜백 엔드포인트**입니다.  
        서버가 인가코드를 받아 바로 토큰 교환 + 사용자 프로필 조회를 수행합니다.  

        ### 동작 흐름
        1. 사용자가 카카오 로그인 버튼 클릭  
        2. 카카오 로그인 성공 → 서버의 `/login/kakao/callback?code=...` 으로 리다이렉트  
        3. 서버가 카카오 API로 프로필을 요청하고, 서비스 가입 상태에 따라 아래 두 가지 중 하나를 반환

        ### 응답 케이스
        - **EXISTING**: 이미 가입된 유저 → 바로 로그인 완료 + AccessToken 반환  
        - **NEED_PROFILE**: TEMP(임시) 유저 -> kakaoId, suggestedNickname 반환 -> 이후 `/login/kakao/complete` 호출 필요

        ### 테스트 방법 (Swagger에서는 직접 호출 불가)
        1. 브라우저 주소창에 아래 주소 입력  
        ```
        https://kauth.kakao.com/oauth/authorize?client_id=4fda49c30ce665f38143fa332b69ac34&redirect_uri=http://localhost:8080/api/users/login/kakao/callback&response_type=code
        ```
        2. 로그인 → 리다이렉트 결과 확인  
        3. NEED_PROFILE이면 다음 API(`/login/kakao/complete`) 진행
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공적으로 로그인 처리",
            content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(name = "기존 유저(EXISTING)", value = """
                {
                  "status": "EXISTING",
                  "kakaoId": "4534750367",
                  "email": "th2194@naver.com",
                  "suggestedNickname": "여행러버",
                  "profileImageUrl": "http://k.kakaocdn.net/...",
                  "login": {
                    "userId": 1,
                    "nickname": "여행러버",
                    "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
                  }
                }
                """),
                @ExampleObject(name = "임시 유저(NEED_PROFILE)", value = """
                {
                  "status": "NEED_PROFILE",
                  "kakaoId": "4534750367",
                  "email": "th2194@naver.com",
                  "suggestedNickname": "김태환",
                  "profileImageUrl": "http://k.kakaocdn.net/...",
                  "login": null
                }
                """)
            })
        ),
        @ApiResponse(responseCode = "400", description = "잘못된 인가코드 / 만료됨",
            content = @Content(mediaType = "application/json", examples = {
                @ExampleObject(value = """
                { "code":"INVALID_AUTH_CODE", "message":"유효하지 않은 인가코드입니다." }
                """)
            }))
    })
    @GetMapping("/login/kakao/callback")
    public KakaoLoginInitResponse kakaoCallback(@RequestParam("code") String code) {
        return kakaoService.init(code);
    }

    @Operation(
        summary = "카카오 닉네임 등록 (TEMP -> USER 전환)",
        description = """
        카카오 TEMP 유저를 정식 USER로 전환합니다.  
        `/login/kakao/callback` 결과가 `NEED_PROFILE`일 때만 호출하세요.

        ### 요청
        ```json
        {
          "kakaoId": "4534750367",
          "nickname": "여행러버"
        }
        ```

        ### 응답
        ```json
        {
          "userId": 1,
          "nickname": "여행러버",
          "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
        }
        ```

        ### 테스트 절차
        1. 브라우저에서 콜백 URL로 로그인 (카카오 로그인 성공 후 redirect)
        ```
        http://localhost:8080/api/users/login/kakao/callback?code={인가코드}
        ```
        2. 응답의 `status`가 NEED_PROFILE -> `kakaoId` 확인
        3. Swagger에서 `/api/users/login/kakao/complete` 실행  
           아래 JSON 예시로 입력:
        ```json
        { "kakaoId": "4534750367", "nickname": "여행러버" }
        ```
        4. 응답으로 AccessToken 확인 -> 로그인 완료 
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정식 USER 전환 완료 + AccessToken 발급",
            content = @Content(schema = @Schema(implementation = UserLoginResponse.class),
                examples = @ExampleObject(value = """
                {
                  "userId": 1,
                  "nickname": "여행러버",
                  "accessToken": "eyJhbGciOiJIUzI1NiJ9..."
                }
                """)))
    })
    @PostMapping("/login/kakao/complete")
    public UserLoginResponse kakaoComplete(@Valid @RequestBody KakaoCompleteRequest request) {
        return kakaoService.complete(request.kakaoId(), request.nickname());
    }
}