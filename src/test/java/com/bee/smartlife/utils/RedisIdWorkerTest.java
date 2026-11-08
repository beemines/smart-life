package com.bee.smartlife.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisIdWorkerTest {
    private static final long EPOCH = 1640995200L;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RedisIdWorker worker;
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(call ->
                counters.computeIfAbsent(call.getArgument(0), ignored -> new AtomicLong()).incrementAndGet());
        worker = new RedisIdWorker();
        ReflectionTestUtils.setField(worker, "stringRedisTemplate", redis);
    }

    @Test
    void highBitsContainElapsedEpochSecondsInsteadOfTheEpochConstant() {
        long before = Instant.now().getEpochSecond() - EPOCH;
        long id = worker.nextId("order");
        long after = Instant.now().getEpochSecond() - EPOCH;

        assertThat(id >>> 32).isBetween(before, after);
        assertThat(id & 0xFFFF_FFFFL).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 0x1_0000_0000L})
    void rejectsSequencesThatDoNotFitTheUnsigned32BitField(long sequence) {
        when(values.increment(anyString())).thenReturn(sequence);
        assertThatIllegalStateException().isThrownBy(() -> worker.nextId("order"));
    }

    @Test
    void rejectsMissingSequenceInsteadOfUnboxingNull() {
        when(values.increment(anyString())).thenReturn(null);
        assertThatIllegalStateException().isThrownBy(() -> worker.nextId("order"));
    }

    @Test
    void redisFailureDoesNotFallBackToAnUncoordinatedLocalId() {
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("test outage"));
        assertThatThrownBy(() -> worker.nextId("order")).isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void utcMidnightChangesTimeBitsEvenWhenTheDailySequenceRestartsAtOne() {
        long before = worker.nextId("order", fixed("2026-11-08T23:59:59Z"));
        long after = worker.nextId("order", fixed("2026-11-09T00:00:00Z"));
        assertThat(after >>> 32).isEqualTo((before >>> 32) + 1);
        assertThat(before & 0xFFFF_FFFFL).isEqualTo(1);
        assertThat(after & 0xFFFF_FFFFL).isEqualTo(1);
        assertThat(after).isNotEqualTo(before);
        verify(values).increment("icr:order:2026:11:08");
        verify(values).increment("icr:order:2026:11:09");
    }

    @Test
    void dateBucketUsesUtcRatherThanTheProvidedClocksLocalDate() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-08T23:59:59Z"), ZoneId.of("Pacific/Auckland"));
        worker.nextId("order", clock);
        verify(values).increment("icr:order:2026:11:08");
        verify(values, never()).increment("icr:order:2026:11:09");
    }

    @Test
    void advancingOneSecondChangesTimeBitsWithoutResettingTheDailySequence() {
        long first = worker.nextId("order", fixed("2026-11-08T10:00:00Z"));
        long second = worker.nextId("order", fixed("2026-11-08T10:00:01Z"));
        assertThat(second >>> 32).isEqualTo((first >>> 32) + 1);
        assertThat(second & 0xFFFF_FFFFL).isEqualTo(2);
    }

    @Test
    void oneThousandConcurrentCallsInTheSameSecondHaveDistinctSequences() throws Exception {
        var ids = new HashSet<Long>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<Long>>();
            for (int i = 0; i < 1000; i++) {
                tasks.add(pool.submit(() -> worker.nextId("concurrent", fixed("2026-11-08T10:00:00Z"))));
            }
            for (Future<Long> task : tasks) ids.add(task.get(10, TimeUnit.SECONDS));
        }
        assertThat(ids).hasSize(1000);
        assertThat(ids).allSatisfy(id -> assertThat(id >>> 32)
                .isEqualTo(Instant.parse("2026-11-08T10:00:00Z").getEpochSecond() - EPOCH));
    }

    @Test
    void recreatedWorkerContinuesTheRetainedSequence() {
        long first = worker.nextId("order", fixed("2026-11-08T10:00:00Z"));
        RedisIdWorker restarted = new RedisIdWorker();
        ReflectionTestUtils.setField(restarted, "stringRedisTemplate", redis);
        long second = restarted.nextId("order", fixed("2026-11-08T10:00:00Z"));
        assertThat(second).isNotEqualTo(first);
        assertThat(second & 0xFFFF_FFFFL).isEqualTo(2);
    }

    @Test
    void rejectsTimesOutsideThePositiveLongLayoutBeforeAllocatingASequence() {
        assertThatIllegalStateException().isThrownBy(() -> worker.nextId("order",
                Clock.fixed(Instant.ofEpochSecond(EPOCH - 1), ZoneOffset.UTC)));
        assertThatIllegalStateException().isThrownBy(() -> worker.nextId("order",
                Clock.fixed(Instant.ofEpochSecond(EPOCH + Integer.MAX_VALUE + 1L), ZoneOffset.UTC)));
        verify(values, never()).increment(anyString());
    }

    @Test
    void acceptsTheEarliestInstantAndLargestPositiveLongComponents() {
        assertThat(worker.nextId("epoch", Clock.fixed(Instant.ofEpochSecond(EPOCH), ZoneOffset.UTC))).isEqualTo(1);
        when(values.increment(anyString())).thenReturn(0xFFFF_FFFFL);
        assertThat(worker.nextId("last", Clock.fixed(Instant.ofEpochSecond(EPOCH + Integer.MAX_VALUE), ZoneOffset.UTC)))
                .isEqualTo(Long.MAX_VALUE);
    }

    private Clock fixed(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }
}
