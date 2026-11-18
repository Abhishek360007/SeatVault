package com.seatvault.service;

import com.seatvault.config.JwtProperties;
import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.dto.auth.AuthResponse;
import com.seatvault.dto.auth.LoginRequest;
import com.seatvault.dto.auth.RegisterRequest;
import com.seatvault.exception.ConflictException;
import com.seatvault.repository.UserRepository;
import com.seatvault.security.JwtService;
import com.seatvault.security.TokenBlocklistService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.seatvault.repository.RefreshTokenRepository;
import com.seatvault.domain.RefreshToken;
import com.seatvault.exception.BadRequestException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Registration and login. The service never returns entities — only the
 * {@link AuthResponse} DTO — and never stores raw passwords.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final TokenBlocklistService tokenBlocklist;
    private final long expirationSeconds;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtProperties jwtProperties,
                       TokenBlocklistService tokenBlocklist) {
        this.tokenBlocklist = tokenBlocklist;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.expirationSeconds = jwtProperties.expirationMinutes() * 60;
    }

    /**
     * Registers a new USER. The unique email is enforced both here (friendly 409)
     * and by the DB constraint (race-safe backstop).
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with that email already exists");
        }

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(),
                Role.USER  // self-registration is always USER; admins are seeded/promoted
        );
        user = userRepository.save(user);
        log.info("Registered new user id={} email={}", user.getId(), user.getEmail());

        return toAuthResponse(user);
    }

    /**
     * Authenticates credentials via the AuthenticationManager (which uses our
     * UserDetailsService + BCrypt), then issues a token. A wrong email or password
     * surfaces as {@code BadCredentialsException} → 401, with no hint as to which
     * was wrong (avoids user enumeration).
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished"));
        log.info("User id={} logged in", user.getId());
        return toAuthResponse(user);
    }

    /**
     * Rotates refresh tokens and issues a new access token. If token reuse is detected,
     * all active refresh tokens for the user are revoked.
     *
     * <p>{@code noRollbackFor}: the reuse branch revokes the user's tokens and then
     * throws. Without it, the exception would roll back the very revocation that
     * protects the account. The row lock ({@code findByTokenForUpdate}) serializes two
     * concurrent refreshes of the same token, so only one can rotate it.
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public AuthResponse refresh(String tokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenForUpdate(tokenValue)
                .orElseThrow(() -> new BadRequestException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            // Token reuse detected (a rotated-away token was presented again, so it may have
            // been stolen). Revoke every active token for this user in one UPDATE.
            Long userId = refreshToken.getUser().getId();
            int revoked = refreshTokenRepository.revokeAllForUser(userId);
            log.warn("Security Alert: Refresh token reuse detected for user id={}. Revoked {} active token(s).",
                    userId, revoked);
            throw new BadRequestException("Invalid refresh token state");
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Refresh token expired");
        }

        // Revoke old token
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        // Generate rotated token family
        User user = refreshToken.getUser();
        String newRefreshTokenValue = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = new RefreshToken(
                newRefreshTokenValue,
                user,
                Instant.now().plus(jwtProperties.refreshExpirationDays(), ChronoUnit.DAYS)
        );
        newRefreshToken.setParentToken(tokenValue);
        refreshTokenRepository.save(newRefreshToken);

        String newAccessToken = jwtService.generateToken(user.getEmail(), user.getRole().name());

        return AuthResponse.bearer(
                newAccessToken,
                newRefreshTokenValue,
                expirationSeconds,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        );
    }

    /**
     * Logs out: revokes the refresh token (PostgreSQL) and, if the caller sent its
     * access token, blocklists that token's {@code jti} in Redis until it would have
     * expired, so it stops working immediately instead of up to 15 minutes later.
     *
     * @param accessToken the raw bearer token, or null if none was sent
     */
    @Transactional
    public void logout(String refreshTokenValue, String accessToken) {
        refreshTokenRepository.findByToken(refreshTokenValue).ifPresent(refreshToken -> {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
        });
        if (accessToken != null) {
            jwtService.parseValid(accessToken).ifPresent((Claims claims) ->
                    tokenBlocklist.revoke(claims.getId(),
                            claims.getExpiration() == null ? null : claims.getExpiration().toInstant()));
        }
    }

    private AuthResponse toAuthResponse(User user) {
        String accessToken = jwtService.generateToken(user.getEmail(), user.getRole().name());
        String refreshTokenValue = UUID.randomUUID().toString();
        RefreshToken refreshToken = new RefreshToken(
                refreshTokenValue,
                user,
                Instant.now().plus(jwtProperties.refreshExpirationDays(), ChronoUnit.DAYS)
        );
        refreshTokenRepository.save(refreshToken);

        return AuthResponse.bearer(
                accessToken,
                refreshTokenValue,
                expirationSeconds,
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name()
        );
    }
}
