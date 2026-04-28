package com.example.ratelimiter;

import com.example.ratelimiter.model.RateLimitStrategy;
import com.example.ratelimiter.service.RateLimiterService;
import com.redis.testcontainers.RedisContainer;
import io.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that spin up a real Redis container via Testcontainers.
 *
 * Tests verify:
 *  - Tokens are consumed correctly for each strategy
 *  - Requests are blocked once the limit is exhausted
 *  - Retry-After is a positive value when blocked
 *  - Bucket reset clears the limit correctly
 *  - Per-user buckets are independent (user A's limit doesn't affect user B)
 */
@SpringBootTest
@Testcontainers
class RateLimiterIntegrationTest {

    @Container
    static RedisContainer redis = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.2"));

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.host", redis::getHost); // for Lettuce client
    }

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void resetBuckets() {
        // Clean state for each test
        rateLimiterService.resetBucket(RateLimitStrategy.STRICT, "test-client");
        rateLimiterService.resetBucket(RateLimitStrategy.STANDARD, "user-a");
        rateLimiterService.resetBucket(RateLimitStrategy.STANDARD, "user-b");
    }

    @Test
    @DisplayName("Strict limiter allows requests within limit and blocks when exhausted")
    void strictLimiter_allowsThenBlocks() {
        // STRICT = 10 req/min
        for (int i = 0; i < 10; i++) {
            ConsumptionProbe probe = rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");
            assertThat(probe.isConsumed())
                    .as("Request %d should be allowed", i + 1)
                    .isTrue();
        }

        // 11th request must be blocked
        ConsumptionProbe blocked = rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");
        assertThat(blocked.isConsumed()).isFalse();
        assertThat(blocked.getRemainingTokens()).isZero();
        assertThat(blocked.getNanosToWaitForRefill()).isPositive();
    }

    @Test
    @DisplayName("retryAfterSeconds returns a positive value when blocked")
    void retryAfterSeconds_isPositiveWhenBlocked() {
        // Exhaust the strict bucket
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");
        }
        ConsumptionProbe blocked = rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");

        assertThat(rateLimiterService.retryAfterSeconds(blocked)).isPositive();
    }

    @Test
    @DisplayName("Per-user buckets are independent — user A exhaustion does not affect user B")
    void perUserBuckets_areIndependent() {
        // Exhaust user-a's standard bucket (60 req/min)
        for (int i = 0; i < 60; i++) {
            rateLimiterService.tryConsume(RateLimitStrategy.STANDARD, "user-a");
        }
        ConsumptionProbe userABlocked = rateLimiterService.tryConsume(RateLimitStrategy.STANDARD, "user-a");
        assertThat(userABlocked.isConsumed()).isFalse();

        // user-b should be completely unaffected
        ConsumptionProbe userBAllowed = rateLimiterService.tryConsume(RateLimitStrategy.STANDARD, "user-b");
        assertThat(userBAllowed.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("Reset clears the bucket so requests are allowed again")
    void resetBucket_clearsLimit() {
        // Exhaust the strict bucket
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");
        }
        assertThat(rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client").isConsumed())
                .isFalse();

        // Reset and verify requests are allowed again
        rateLimiterService.resetBucket(RateLimitStrategy.STRICT, "test-client");

        ConsumptionProbe afterReset = rateLimiterService.tryConsume(RateLimitStrategy.STRICT, "test-client");
        assertThat(afterReset.isConsumed()).isTrue();
    }

    @Test
    @DisplayName("Standard limiter remaining tokens decrease with each request")
    void standardLimiter_remainingTokensDecrement() {
        ConsumptionProbe first = rateLimiterService.tryConsume(RateLimitStrategy.STANDARD, "user-a");
        ConsumptionProbe second = rateLimiterService.tryConsume(RateLimitStrategy.STANDARD, "user-a");

        assertThat(first.isConsumed()).isTrue();
        assertThat(second.isConsumed()).isTrue();
        assertThat(second.getRemainingTokens()).isLessThan(first.getRemainingTokens());
    }
}
