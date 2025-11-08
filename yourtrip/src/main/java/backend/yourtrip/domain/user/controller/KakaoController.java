package backend.yourtrip.domain.user.controller;

import backend.yourtrip.domain.user.dto.response.KakaoUserResponse;
import backend.yourtrip.domain.user.service.KakaoService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class KakaoController {

    private final KakaoService kakaoService;

    @Operation(summary = "카카오 로그인 / 회원가입")
    @GetMapping("/login/kakao")
    public ResponseEntity<Void> kakaoLogin(@RequestParam("code") String code) {
        KakaoUserResponse response = kakaoService.kakaoLogin(code);

        URI redirectUri = URI.create("http://localhost:3000/oauth/kakao?token=" + response.accessToken());
        return ResponseEntity.status(HttpStatus.FOUND).location(redirectUri).build();
    }
}