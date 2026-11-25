package com.example.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * OPS-4: every log line written while a request is handled carries the user ID when it is known.
 * Runs after Spring Security, so the token has already been validated.
 */
@Component
public class UserIdLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean known = authentication != null && authentication.getPrincipal() instanceof Jwt;
        if (known) {
            MDC.put("userId", ((Jwt) authentication.getPrincipal()).getSubject());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (known) {
                MDC.remove("userId");
            }
        }
    }
}
