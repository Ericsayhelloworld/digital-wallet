package com.example.auth.api;

import com.example.auth.api.Dtos.LoginRequest;
import com.example.auth.api.Dtos.LoginResponse;
import com.example.auth.api.Dtos.RegisterRequest;
import com.example.auth.error.ApiException;
import com.example.auth.user.User;
import com.example.auth.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final LoginRateLimiter rateLimiter;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                       LoginRateLimiter rateLimiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
    }

    public User register(RegisterRequest request) {
        // AUTH-1: at most 72 bytes in UTF-8 (BCrypt ignores everything after byte 72)
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.validation("password", "must be at most 72 bytes in UTF-8");
        }
        if (users.existsByUsername(request.username())) {
            throw usernameTaken();
        }
        User user = new User(request.username(), passwordEncoder.encode(request.password()),
                request.displayName(), List.of("USER"));
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw usernameTaken(); // two registrations with the same name at the same moment
        }
    }

    public LoginResponse login(LoginRequest request) {
        rateLimiter.check(request.username()); // SEC-9: before anything else, also for unknown usernames
        Optional<User> user = users.findByUsername(request.username());
        // AUTH-6: same answer for an unknown user and a wrong password
        if (user.isEmpty() || !passwordEncoder.matches(request.password(), user.get().getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Invalid credentials", "The username or the password is wrong.");
        }
        return tokens.issue(user.get());
    }

    public User me(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                "UNAUTHENTICATED", "Unauthenticated", "The user of this token no longer exists."));
    }

    private static ApiException usernameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN",
                "Username taken", "This username is already registered.");
    }
}
