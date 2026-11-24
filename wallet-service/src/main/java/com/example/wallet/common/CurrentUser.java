package com.example.wallet.common;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** SEC-2: who the caller is comes only from the token, never from the request body. */
public record CurrentUser(UUID id, String username, String displayName) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsString("name"));
    }
}
