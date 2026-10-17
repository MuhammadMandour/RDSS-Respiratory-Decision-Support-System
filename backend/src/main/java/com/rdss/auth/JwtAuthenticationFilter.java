package com.rdss.auth;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.Role;
import com.rdss.patients.PatientRepository;
import com.rdss.users.AppUserRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying {@code Authorization: Bearer <jwt>}. The user is re-loaded
 * on every request so disabling an account or changing a role takes effect immediately.
 * Invalid tokens simply leave the request unauthenticated (the entry point returns 401).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final AppUserRepository users;
    private final PatientRepository patients;

    public JwtAuthenticationFilter(JwtService jwtService, AppUserRepository users, PatientRepository patients) {
        this.jwtService = jwtService;
        this.users = users;
        this.patients = patients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.validate(header.substring(BEARER.length()).trim())
                    .flatMap(users::findById)
                    .filter(u -> u.isEnabled())
                    .ifPresent(u -> {
                        Long patientId = u.getRole() == Role.PATIENT
                                ? patients.findByUserId(u.getId()).map(p -> p.getId()).orElse(null)
                                : null;
                        var principal = new AuthenticatedUser(u.getId(), u.getUsername(), u.getFullName(),
                                u.getRole(), patientId);
                        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                                List.of(new SimpleGrantedAuthority(u.getRole().authority())));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
