package com.momentweaver.auth.jwt;

import com.momentweaver.common.BusinessException;
import com.momentweaver.common.ResultCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 工具：签发 Access / Refresh Token，解析 userId。
 */
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties props;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.getSecret()));
    }

    public String generateAccessToken(Long userId) {
        return generate(userId, "access", props.getAccessTtlSeconds() * 1000L);
    }

    public String generateRefreshToken(Long userId) {
        return generate(userId, "refresh", props.getRefreshTtlSeconds() * 1000L);
    }

    private String generate(Long userId, String type, long ttlMs) {
        Date now = new Date();
        return Jwts.builder()
            .issuer(props.getIssuer())
            .subject(String.valueOf(userId))
            .claim("typ", type)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + ttlMs))
            .signWith(key(), Jwts.SIG.HS256)
            .compact();
    }

    public Long parseUserId(String token) {
        try {
            Claims c = Jwts.parser()
                .requireIssuer(props.getIssuer())
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            return Long.parseLong(c.getSubject());
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }
    }

    public long getAccessTtlSeconds() {
        return props.getAccessTtlSeconds();
    }
}
