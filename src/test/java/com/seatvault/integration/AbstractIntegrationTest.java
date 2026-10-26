package com.seatvault.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that exercises the application against REAL
 * Postgres and Redis containers (Testcontainers). Flyway runs the production
 * migrations against the fresh Postgres container, and Hibernate validates the
 * mapping against that schema — so these tests catch schema/entity drift too.
 *
 * <p>Containers are {@code static} so they're started once and shared across all
 * test methods in a class (Testcontainers + JUnit 5 lifecycle), then torn down
 * automatically. Connection details are injected via {@link DynamicPropertySource}
 * so the same {@code application.yml} env-var wiring works unchanged.
 *
 * <p>Requires a running Docker daemon; runs in CI and locally when Docker Desktop
 * is up. These are {@code *IT} classes, executed by Failsafe under {@code mvn verify}.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // The pool must exceed the concurrency test's thread count (24) so the ONLY
        // contention point is the seat row-lock — what we're actually testing — and not
        // connection acquisition. With the production default (20 < 24), threads block
        // waiting for a connection, muddying the "exactly one winner" timing and stalling
        // between-test cleanup.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 40);
    }
}
