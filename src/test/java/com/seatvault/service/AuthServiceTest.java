package com.seatvault.service;

import com.seatvault.config.JwtProperties;
import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.dto.auth.AuthResponse;
import com.seatvault.dto.auth.LoginRequest;
import com.seatvault.dto.auth.RegisterRequest;
import com.seatvault.exception.ConflictException;
import com.seatvault.repository.RefreshTokenRepository;
import com.seatvault.repository.UserRepository;
import com.seatvault.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @Mock RefreshTokenRepository refreshTokenRepository;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager,
                jwtService, refreshTokenRepository, new JwtProperties("secret-secret-secret-secret-secret-1234", 60, 7));
    }

    @Test
    void registerHashesPasswordSavesUserAndReturnsToken() {
        var request = new RegisterRequest("bob@example.com", "password123", "Bob Smith");
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("HASHED");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });
        when(jwtService.generateToken("bob@example.com", "USER")).thenReturn("jwt-token");

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.role()).isEqualTo("USER");
        assertThat(response.expiresInSeconds()).isEqualTo(3600L);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("HASHED");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("dup@example.com", "password123", "Dup")))
                .isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(jwtService);
    }

    @Test
    void loginAuthenticatesAndIssuesToken() {
        User user = user();
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("bob@example.com");
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken("bob@example.com", "USER")).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("bob@example.com", "password123"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.email()).isEqualTo("bob@example.com");
    }

    @Test
    void loginPropagatesBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtService);
    }

    private User user() {
        User user = new User("bob@example.com", "HASHED", "Bob Smith", Role.USER);
        user.setId(7L);
        return user;
    }
}
