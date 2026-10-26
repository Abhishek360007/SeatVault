package com.seatvault.config;

import com.seatvault.domain.Role;
import com.seatvault.domain.User;
import com.seatvault.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps a single ADMIN account on startup if one does not already exist.
 *
 * <p>We seed via code (not a Flyway migration) so the password is hashed by the
 * same {@link PasswordEncoder} the app uses — a migration would have to embed a
 * pre-computed hash, which is brittle. The runner is idempotent: it only inserts
 * when the configured admin email is absent, so it's safe on every boot.
 *
 * <p>Credentials come from env vars; defaults are dev-only and must be overridden
 * in production.
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminSeeder(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${app.admin.email:admin@seatvault.local}") String adminEmail,
                       @Value("${app.admin.password:admin12345}") String adminPassword,
                       @Value("${app.admin.full-name:Platform Admin}") String adminName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(adminEmail)) {
            log.debug("Admin account {} already present; skipping seed", adminEmail);
            return;
        }
        User admin = new User(
                adminEmail,
                passwordEncoder.encode(adminPassword),
                adminName,
                Role.ADMIN
        );
        userRepository.save(admin);
        log.info("Seeded ADMIN account: {} (override app.admin.* in production!)", adminEmail);
    }
}
