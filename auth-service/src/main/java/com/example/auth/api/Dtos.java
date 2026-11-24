package com.example.auth.api;

import com.example.auth.user.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    /** AUTH-1 */
    public record RegisterRequest(
            @NotNull
            @Pattern(regexp = "^[a-z0-9_]{3,20}$",
                    message = "must be 3 to 20 characters: lowercase letters, digits and underscore")
            String username,

            @NotNull
            @Size(min = 8, max = 72)
            String password,

            @NotBlank
            @Size(max = 50)
            String displayName) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    }

    public record UserResponse(String id, String username, String displayName, List<String> roles) {
        public static UserResponse from(User user) {
            return new UserResponse(user.getId().toString(), user.getUsername(),
                    user.getDisplayName(), user.getRoleList());
        }
    }
}
