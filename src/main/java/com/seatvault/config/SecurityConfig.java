package com.seatvault.config;

import com.seatvault.security.JwtAuthenticationFilter;
import com.seatvault.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * Stateless, JWT-based security.
 *
 * <ul>
 *   <li>CSRF disabled — there are no cookies/sessions, so there is no CSRF surface;
 *       the JWT must be presented explicitly in a header.</li>
 *   <li>Session policy STATELESS — Spring never creates an HttpSession.</li>
 *   <li>Our {@link JwtAuthenticationFilter} runs before the username/password filter
 *       to authenticate from the bearer token.</li>
 *   <li>{@code @EnableMethodSecurity} turns on {@code @PreAuthorize} for fine-grained
 *       role checks on admin endpoints (Phase 4).</li>
 * </ul>
 *
 * <p>Authorization rules are intentionally coarse here (public auth + docs, the
 * rest authenticated); method-level {@code @PreAuthorize("hasRole('ADMIN')")}
 * guards the admin-only operations so the rules live next to the code they protect.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Endpoints reachable without authentication. */
    private static final String[] PUBLIC_GET = {
            "/api/v1/events/**",            // public browse/search (Phase 4)
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/prometheus",
            // ---- Static single-page UI (Phase 10) ----
            // The SPA shell is public; the data APIs it calls stay protected by JWT.
            // The browser stores the token and attaches it to API requests itself.
            "/",
            "/index.html",
            "/app.js",
            "/styles.css",
            "/vendor/**",                   // bundled SockJS + STOMP clients
            "/favicon.ico"
    };

    private static final String[] PUBLIC_POST = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // Provider webhooks can't log in; they're authenticated by HMAC signature instead.
            "/api/v1/payments/webhook/**"
    };

    /**
     * Content-Security-Policy compatible with both the static SPA and Swagger UI.
     * <ul>
     *   <li>{@code style-src 'unsafe-inline'} + Google Fonts: the SPA uses inline
     *       {@code style="..."} attributes (e.g. poster gradients) and loads fonts
     *       from the Google Fonts CDN.</li>
     *   <li>{@code script-src 'unsafe-inline'}: springdoc's Swagger UI ships a small
     *       inline bootstrap script. The SPA itself uses only the external
     *       {@code /app.js}. Documented tradeoff: dropping Swagger would let us tighten
     *       this to {@code 'self'} (README ▸ Security).</li>
     *   <li>{@code img-src data:}: inline SVG favicon + the generated ticket QR.</li>
     *   <li>{@code frame-ancestors 'none'}: clickjacking defence.</li>
     * </ul>
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            // Razorpay's checkout widget: its script, plus the iframe it opens.
            "script-src 'self' 'unsafe-inline' https://checkout.razorpay.com",
            "frame-src https://api.razorpay.com https://checkout.razorpay.com",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' data:",
            "connect-src 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          CorsProperties corsProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Security headers. Spring already sets X-Content-Type-Options: nosniff and
                // a default Cache-Control; we add a CSP + Referrer-Policy and pin frames.
                .headers(headers -> headers
                        // frame-ancestors 'none' (CSP) + X-Frame-Options DENY = clickjacking defence.
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                                .preload(true)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, PUBLIC_GET).permitAll()
                        // Live seat-map feed. Public like the seat list it mirrors; SockJS uses
                        // both GET and POST transports, so it's matched for all methods. Clients
                        // can only subscribe, never publish (see WebSocketConfig).
                        .requestMatchers("/ws-seatmap/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Authorization"));
        if (corsProperties.allowedOrigins() != null && corsProperties.allowedOrigins().contains("*")) {
            configuration.setAllowCredentials(false);
        } else {
            configuration.setAllowCredentials(true);
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** BCrypt is the industry-standard adaptive hash for passwords. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Exposes the AuthenticationManager so the login flow can authenticate credentials. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
