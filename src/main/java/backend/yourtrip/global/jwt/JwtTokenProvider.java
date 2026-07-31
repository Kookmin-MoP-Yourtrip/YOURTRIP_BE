package backend.yourtrip.global.jwt;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.security.CustomUserDetails;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final Key key;
    private final long ACCESS_TOKEN_VALIDITY = 1000L * 60 * 60;
    private final long REFRESH_TOKEN_VALIDITY = 1000L * 60 * 60 * 24 * 14;

    public JwtTokenProvider(@Value("${jwt.secret}") String secretKey) {
        this.key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String email) {
        return generateToken(userId, email, ACCESS_TOKEN_VALIDITY, "access");
    }

    public String createRefreshToken(Long userId, String email) {
        return generateToken(userId, email, REFRESH_TOKEN_VALIDITY, "refresh");
    }

    private String generateToken(Long userId, String email, long validity, String type) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validity);

        return Jwts.builder()
            .setSubject(String.valueOf(userId))
            .claim("email", email)
            .claim("typ", type)
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public String getTokenType(String token) {
        Object typ = parseClaims(token).get("typ");
        return typ != null ? typ.toString() : "";
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .getBody();
    }

    public Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() ||
            auth.getPrincipal().equals("anonymousUser")) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
        return principal.getUserId();
    }
}