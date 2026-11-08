package com.kbconsole.auth;

import com.kbconsole.common.ApiException;
import com.kbconsole.common.ErrorCodes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class TokenService {
    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(@Value("${kb.token-secret}") String secret,
                        @Value("${kb.token-ttl-seconds:86400}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(Long userId) {
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds;
        String payload = userId + ":" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw unauthorized();
        }
        String expected = sign(parts[0]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw unauthorized();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw unauthorized();
        }
        String[] fields = payload.split(":");
        if (fields.length != 2) {
            throw unauthorized();
        }
        try {
            long expiresAt = Long.parseLong(fields[1]);
            if (expiresAt < Instant.now().getEpochSecond()) {
                throw unauthorized();
            }
            return Long.parseLong(fields[0]);
        } catch (NumberFormatException ex) {
            throw unauthorized();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign token", ex);
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, "登录已失效，请重新登录");
    }
}
