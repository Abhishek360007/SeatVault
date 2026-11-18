package com.seatvault.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenBlocklistServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;

    TokenBlocklistService blocklist;

    @BeforeEach
    void setUp() {
        blocklist = new TokenBlocklistService(redis);
    }

    @Test
    void revokeStoresTheJtiForTheTokensRemainingLifetime() {
        when(redis.opsForValue()).thenReturn(values);

        blocklist.revoke("jti-1", Instant.now().plusSeconds(600));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("jwt:blocklist:jti-1"), eq("1"), ttl.capture());
        assertThat(ttl.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void revokeIgnoresAlreadyExpiredOrIdLessTokens() {
        blocklist.revoke("jti-1", Instant.now().minusSeconds(1));
        blocklist.revoke(null, Instant.now().plusSeconds(600));

        verify(redis, never()).opsForValue();
    }

    @Test
    void isRevokedReflectsTheBlocklist() {
        when(redis.hasKey("jwt:blocklist:gone")).thenReturn(true);
        when(redis.hasKey("jwt:blocklist:live")).thenReturn(false);

        assertThat(blocklist.isRevoked("gone")).isTrue();
        assertThat(blocklist.isRevoked("live")).isFalse();
        assertThat(blocklist.isRevoked(null)).isFalse();
    }

    @Test
    void isRevokedFailsOpenWhenRedisIsDown() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(blocklist.isRevoked("jti-1")).isFalse();
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
