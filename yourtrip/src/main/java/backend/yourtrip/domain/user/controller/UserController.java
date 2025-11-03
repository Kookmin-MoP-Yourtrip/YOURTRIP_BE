package backend.yourtrip.domain.user.controller;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@Tag(name = "User API", description = "회원가입 및 로그인 API")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "회원가입 API")
    @PostMapping("/signup")
    public ResponseEntity<UserSignupResponse> signup(@RequestBody UserSignupRequest request) {
        return ResponseEntity.ok(userService.signup(request));
    }

    @Operation(summary = "로그인 API")
    @PostMapping("/login")
    public ResponseEntity<UserLoginResponse> login(@RequestBody UserLoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }
}