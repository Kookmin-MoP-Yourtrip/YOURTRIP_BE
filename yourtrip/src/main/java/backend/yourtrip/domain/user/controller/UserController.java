package backend.yourtrip.domain.user.controller;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
@Tag(name = "User API", description = "회원가입 및 로그인 API")
public class UserController {

    private final UserService userService;

    @Operation(summary = "회원가입 API")
    @PostMapping("/signup")
    public UserSignupResponse signup(@RequestBody UserSignupRequest request) {
        return userService.signup(request);
    }

    @Operation(summary = "로그인 API")
    @PostMapping("/login")
    public UserLoginResponse login(@RequestBody UserLoginRequest request) {
        return userService.login(request);
    }

    @Operation(summary = "Access Token 재발급 API")
    @PostMapping("/refresh")
    public UserLoginResponse refresh(@RequestHeader("Authorization") String refreshToken) {
        return userService.refresh(refreshToken);
    }
}