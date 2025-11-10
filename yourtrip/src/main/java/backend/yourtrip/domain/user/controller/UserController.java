package backend.yourtrip.domain.user.controller;

import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.request.UserSignupRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.service.KakaoService;
import backend.yourtrip.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * UserController
 *
 * 회원 관련 API (회원가입 / 로그인 / 카카오 로그인)
 * 프론트엔드가 참고해야 할 제약조건, 예외사항, 테스트 방법을 모두 포함한 Swagger 문서화 버전
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final KakaoService kakaoService;

    // ==========================
    //  1. 이메일 회원가입
    // ==========================
    @Operation(
        summary = "이메일 회원가입",
        description = """
        ### 제약조건
        - 이메일: RFC 5322 형식, 중복 불가
        - 비밀번호: 최소 8자 이상, 공백 불가 (영문/숫자/특수문자 조합 권장)
        - 닉네임: 최소 1자, 최대 20자
        
        ### 예외상황
        - `EMAIL_ALREADY_EXIST(400)`: 이미 가입된 이메일
        - `INVALID_REQUEST_FIELD(400)`: 필드 유효성 오류(빈 값, 포맷 불일치 등)
        
        ### 테스트 방법
        - Swagger / Postman 모두 테스트 가능
        - 예시 요청:
        ```json
        {
          "email": "user@example.com",
          "password": "abcd1234!",
          "nickname": "여행러버"
        }
        ```
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "회원가입 성공",
            content = @Content(schema = @Schema(implementation = UserSignupResponse.class),
                examples = @ExampleObject(value = """
                {
                  "userId": 1,
                  "email": "user@example.com",
                  "nickname": "여행러버",
                  "createdAt": "2025-11-09T01:00:00"
                }
                """))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청/중복 이메일",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-09T01:00:00",
                  "code": "EMAIL_ALREADY_EXIST",
                  "message": "이미 가입된 이메일입니다."
                }
                """)))
    })
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSignupResponse signup(@RequestBody UserSignupRequest request) {
        return userService.signup(request);
    }

    // ==========================
    //  2. 이메일 로그인
    // ==========================
    @Operation(
        summary = "이메일 로그인",
        description = """
        ### 제약조건
        - 이메일 / 비밀번호 모두 필수 입력값
        
        ### 예외상황
        - `EMAIL_NOT_FOUND(400)`: 가입되지 않은 이메일
        - `NOT_MATCH_PASSWORD(400)`: 비밀번호 불일치
        
        ### 테스트 방법
        - Swagger / Postman 모두 가능
        - 로그인 성공 시 JWT Access Token이 발급됩니다.
        - Authorize 버튼 클릭 후 `Bearer {token}` 입력하면 인증 API 호출 가능
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공",
            content = @Content(schema = @Schema(implementation = UserLoginResponse.class),
                examples = @ExampleObject(value = """
                {
                  "userId": 1,
                  "nickname": "여행러버",
                  "accessToken": "eyJhbGciOi..."
                }
                """))),
        @ApiResponse(responseCode = "400", description = "이메일/비밀번호 오류",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-09T01:10:00",
                  "code": "NOT_MATCH_PASSWORD",
                  "message": "비밀번호가 일치하지 않습니다."
                }
                """)))
    })
    @PostMapping("/login")
    public UserLoginResponse login(@RequestBody UserLoginRequest request) {
        return userService.login(request);
    }

    // ==========================
    //  3. 카카오 로그인 / 회원가입
    // ==========================
    @Operation(
        summary = "카카오 로그인 / 회원가입",
        description = """
        ### 개요
        프론트엔드에서 카카오 로그인 페이지로 리다이렉트 → 카카오로부터 인가코드(`code`)를 발급받은 뒤,
        해당 코드를 서버 `/api/users/login/kakao` 엔드포인트에 전달하여 로그인/회원가입을 처리합니다.
        
        ### 처리 로직
        1️. 인가코드(`code`) 수신  
        2️. 서버에서 카카오 토큰 API 호출 (`https://kauth.kakao.com/oauth/token`)  
        3️. 사용자 정보 조회 (`https://kapi.kakao.com/v2/user/me`)  
        4️. DB 조회 → 신규면 회원가입 / 기존이면 로그인  
        5️. JWT Access Token 발급 후 응답 반환
        
        ### 예외상황
        | 코드 | 상태 | 설명 |
        |------|------|------|
        | `INVALID_AUTH_CODE` | 400 | 잘못되었거나 만료된 인가코드 |
        | `TOKEN_REQUEST_FAILED` | 502 | 카카오 토큰 발급 실패 (invalid_grant 등) |
        | `USERINFO_REQUEST_FAILED` | 502 | 카카오 프로필 요청 실패 |
        | `USER_SAVE_FAILED` | 500 | 신규 회원 DB 저장 실패 |
        
        ### 제약조건
        - `code`는 1회용이며, 재사용 시 무조건 실패 (카카오 정책)
        - `redirect_uri`는 카카오 개발자 콘솔 설정과 완전히 일치해야 함
        - 클라이언트에서 반드시 새 code를 발급받아야 함
        - Swagger / Postman에서는 실제 로그인 테스트가 불가능합니다.
          (인가코드 발급 리다이렉트 과정이 지원되지 않기 때문)
        
        ### 테스트 방법
        1️. 브라우저에서 직접 접속:
        ```
        https://kauth.kakao.com/oauth/authorize?client_id=4fda49c30ce665f38143fa332b69ac34&redirect_uri=http://localhost:8080/api/users/login/kakao&response_type=code
        ```
        2️. 카카오 로그인 후, 리다이렉트 URL의 `code` 값을 복사  
        3️. 새 code를 이용해 Postman에서 GET 호출
        ```
        GET http://localhost:8080/api/users/login/kakao?code={새로운 code}
        ```
        첫 호출만 200 OK (JWT 반환), 이후 재사용 시 502 TOKEN_REQUEST_FAILED
        
        ### Swagger/Postman 제한
        - Swagger에서는 카카오 로그인 창을 띄울 수 없으므로 실행 테스트 불가
        - 단, Swagger에서는 성공/오류 응답 스펙 및 제약조건 문서 확인만 가능
        """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "카카오 로그인/회원가입 성공",
            content = @Content(schema = @Schema(implementation = UserLoginResponse.class),
                examples = @ExampleObject(value = """
                {
                  "userId": 3,
                  "nickname": null,
                  "accessToken": "eyJhbGciOi..."
                }
                """))),
        @ApiResponse(responseCode = "400", description = "인가 코드 오류",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-09T01:15:00",
                  "code": "INVALID_AUTH_CODE",
                  "message": "유효하지 않은 인가코드입니다."
                }
                """))),
        @ApiResponse(responseCode = "502", description = "카카오 서버 통신 실패 (invalid_grant 등)",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-09T01:15:00",
                  "code": "TOKEN_REQUEST_FAILED",
                  "message": "카카오 토큰 발급에 실패했습니다."
                }
                """))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류 (회원 DB 저장 / JWT 발급 실패)",
            content = @Content(mediaType = "application/json",
                examples = @ExampleObject(value = """
                {
                  "timestamp": "2025-11-09T01:16:00",
                  "code": "USER_SAVE_FAILED",
                  "message": "회원정보 저장 중 오류가 발생했습니다."
                }
                """)))
    })
    @GetMapping("/login/kakao")
    public UserLoginResponse kakaoLogin(
        @RequestParam("code") @NotBlank String code,
        @RequestParam(value = "nickname", required = false) String nickname
    ) {
        return kakaoService.kakaoLogin(code, nickname);
    }
}