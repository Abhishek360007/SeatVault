package com.seatvault.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that exercises the application against REAL
 * Postgres and Redis containers (Testcontainers). Flyway runs the production
 * migrations against the fresh Postgres container, and Hibernate validates the
 * mapping against that schema — so these tests catch schema/entity drift too.
 *
 * <p>Singleton-container pattern: the containers are started once, in a static
 * initializer, and shared by every {@code *IT} class in the JVM; Testcontainers'
 * Ryuk sidecar removes them when the JVM exits. This deliberately avoids
 * {@code @Testcontainers}/{@code @Container}, which stops the containers after
 * each test class — while Spring's test-context cache keeps reusing the same
 * application context, so the second IT class would get a context still pointing
 * at the first class's (now stopped) Postgres.
 *
 * <p>Connection details are injected via {@link DynamicPropertySource} so the same
 * {@code application.yml} env-var wiring works unchanged.
 *
 * <p>Requires a running Docker daemon; runs in CI and locally when Docker Desktop
 * is up. These are {@code *IT} classes, executed by Failsafe under {@code mvn verify}.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** Catches the app's SMTP so email tests can read what was sent through Mailpit's API. */
    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.21.8")).withExposedPorts(1025, 8025);

    static {
        POSTGRES.start();
        REDIS.start();
        MAILPIT.start();
    }

    /** Base URL of Mailpit's HTTP API, e.g. {@code http://localhost:32768}. */
    protected static String mailpitApi() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        // Tests drive the email dispatcher and reminder sweep themselves; keep the
        // background timers from racing them.
        registry.add("app.notifications.dispatch-interval-ms", () -> 3_600_000);
        registry.add("app.notifications.reminder-interval-ms", () -> 3_600_000);
        // The pool must exceed the concurrency test's thread count (24) so the ONLY
        // contention point is the seat row-lock — what we're actually testing — and not
        // connection acquisition. With the production default (20 < 24), threads block
        // waiting for a connection, muddying the "exactly one winner" timing and stalling
        // between-test cleanup.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 40);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Rows that reference bookings must go before the bookings themselves; call this
     * first in any cleanup that deletes bookings, since IT classes share one database.
     */
    protected void deleteBookingDependents() {
        jdbcTemplate.update("DELETE FROM notifications");
        jdbcTemplate.update("DELETE FROM ticket_checkins");
        jdbcTemplate.update("DELETE FROM payments");
    }
}
