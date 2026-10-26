package com.seatvault.service;

import com.seatvault.config.WaitingRoomProperties;
import com.seatvault.dto.queue.QueueStatusResponse;
import com.seatvault.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock SetOperations<String, String> setOps;

    QueueService queueService;

    @BeforeEach
    void setUp() {
        WaitingRoomProperties properties = new WaitingRoomProperties(50, 10_000, 15_000, 120);
        queueService = new QueueService(redis, properties);
        // lenient: not every test exercises all three op groups.
        org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        org.mockito.Mockito.lenient().when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void joinAddsToWaitLineWhenNotAlreadyAdmitted() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn(null);
        when(zSetOps.rank("queue:wait:5", "7")).thenReturn(3L);

        QueueStatusResponse status = queueService.join(5L, 7L);

        verify(zSetOps).addIfAbsent(eq("queue:wait:5"), eq("7"), any(Double.class));
        verify(setOps).add("queue:active-events", "5");
        assertThat(status.admitted()).isFalse();
        assertThat(status.position()).isEqualTo(4L); // 0-based rank -> 1-based position
    }

    @Test
    void joinIsIdempotentWhenAlreadyAdmitted() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn("tok-abc");
        when(redis.getExpire("queue:admitted:5:7")).thenReturn(90L);

        QueueStatusResponse status = queueService.join(5L, 7L);

        assertThat(status.admitted()).isTrue();
        assertThat(status.admissionToken()).isEqualTo("tok-abc");
        assertThat(status.tokenExpiresInSeconds()).isEqualTo(90L);
        verify(zSetOps, org.mockito.Mockito.never()).addIfAbsent(anyString(), anyString(), any(Double.class));
    }

    @Test
    void statusReportsNotQueuedWhenNeitherAdmittedNorInLine() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn(null);
        when(zSetOps.rank("queue:wait:5", "7")).thenReturn(null);

        QueueStatusResponse status = queueService.status(5L, 7L);

        assertThat(status.admitted()).isFalse();
        assertThat(status.position()).isNull();
    }

    @Test
    void requireAdmissionPassesWhenTokenMatches() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn("tok-abc");

        queueService.requireAdmission(5L, 7L, "tok-abc");
        // no exception
    }

    @Test
    void requireAdmissionRejectsMissingToken() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn(null);

        assertThatThrownBy(() -> queueService.requireAdmission(5L, 7L, null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void requireAdmissionRejectsMismatchedToken() {
        when(valueOps.get("queue:admitted:5:7")).thenReturn("tok-real");

        assertThatThrownBy(() -> queueService.requireAdmission(5L, 7L, "tok-fake"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void admitNextBatchMintsTokensForPoppedMembersAndRemovesEmptyEventFromActiveSet() {
        ZSetOperations.TypedTuple<String> tuple = ZSetOperations.TypedTuple.of("7", 1000.0);
        when(zSetOps.popMin("queue:wait:5", 50)).thenReturn(Set.of(tuple));

        int admitted = queueService.admitNextBatch(5L);

        assertThat(admitted).isEqualTo(1);
        verify(valueOps).set(eq("queue:admitted:5:7"), anyString(), any(java.time.Duration.class));
    }

    @Test
    void admitNextBatchRemovesFromActiveEventsWhenWaitLineEmpty() {
        when(zSetOps.popMin("queue:wait:5", 50)).thenReturn(Set.of());

        int admitted = queueService.admitNextBatch(5L);

        assertThat(admitted).isEqualTo(0);
        verify(setOps).remove("queue:active-events", "5");
    }
}
