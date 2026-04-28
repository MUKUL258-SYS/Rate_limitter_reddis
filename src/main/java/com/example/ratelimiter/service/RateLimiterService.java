package com.example.ratelimiter.service;

import com.example.ratelimiter.model.RateLimitStrategy;
import io.bucket4j.Bucket;
import io.bucket4j.BucketConfiguration;
import io.bucket4j.ConsumptionProbe;
import io.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Core service for distributed rate limiting.
 *
 * Bucket state is stored in Redis via LettuceBasedProxyManager.
 * Multiple application instances share the same bucket — no double-counting.
 *
 * Key design decisions:
 *  - Bucket keys are namespaced: {strategy}:{clientKey}
 *  - ConsumptionProbe is used instead of tryConsume() for richer response data
 *  - Micrometer counters track allowed/blocked requests per strategy for alerting
 *  - Retry-After is computed from nanosToWaitForRefill and returned to callers
 */
@Slf4j
@Service
public class RateLimiterService {

    private final ProxyManager<String> proxyManager;
    private final Map<RateLimitStrategy, Supplier<BucketConfiguration>> strategyConfigMap;
    private final MeterRegistry meterRegistry;

    public RateLimiterService(
            ProxyManager<String> proxyManager,
            Supplier<BucketConfiguration> standardBucketConfig,
            Supplier<BucketConfiguration> burstBucketConfig,
            Supplier<BucketConfiguration> strictBucketConfig,
            Supplier<BucketConfiguration> slidingWindowBucketConfig,
            Supplier<BucketConfiguration> dailyApiBucketConfig,
            MeterRegistry meterRegistry) {

        this.proxyManager = proxyManager;
        this.meterRegistry = meterRegistry;
        this.strategyConfigMap = Map.of(
                RateLimitStrategy.STANDARD, standardBucketConfig,
                RateLimitStrategy.BURST,    burstBucketConfig,
                RateLimitStrategy.STRICT,   strictBucketConfig,
                RateLimitStrategy.SLIDING,  slidingWindowBucketConfig,
                RateLimitStrategy.DAILY,    dailyApiBucketConfig
        );
    }

    /**
     * Attempts to consume one token from the bucket identified by (strategy, clientKey).
     *
     * @param strategy  the rate limiting strategy to apply
     * @param clientKey identifier for the rate limit subject (IP, userId, apiKey, etc.)
     * @return ConsumptionProbe with remaining tokens and wait time if blocked
     */
    public ConsumptionProbe tryConsume(RateLimitStrategy strategy, String clientKey) {
        String bucketKey = buildKey(strategy, clientKey);
        Supplier<BucketConfiguration> configSupplier = strategyConfigMap.get(strategy);

        Bucket bucket = proxyManager.builder()
                .build(bucketKey, configSupplier);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        recordMetrics(strategy, probe.isConsumed());

        log.debug("RateLimit check — key={} strategy={} allowed={} remaining={}",
                bucketKey, strategy, probe.isConsumed(), probe.getRemainingTokens());

        return probe;
    }

    /**
     * Resets (deletes) the Redis key for a bucket, effectively resetting the limit.
     * Useful for admin operations or test teardown.
     */
    public void resetBucket(RateLimitStrategy strategy, String clientKey) {
        String bucketKey = buildKey(strategy, clientKey);
        proxyManager.removeProxy(bucketKey);
        log.info("Reset bucket key={}", bucketKey);
    }

    /**
     * Converts nanosToWaitForRefill to whole seconds, rounding up.
     */
    public long retryAfterSeconds(ConsumptionProbe probe) {
        return TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String buildKey(RateLimitStrategy strategy, String clientKey) {
        return strategy.name().toLowerCase() + ":" + clientKey;
    }

    private void recordMetrics(RateLimitStrategy strategy, boolean allowed) {
        String strategyTag = strategy.name().toLowerCase();
        if (allowed) {
            Counter.builder("rate_limiter.requests.allowed")
                    .tag("strategy", strategyTag)
                    .register(meterRegistry)
                    .increment();
        } else {
            Counter.builder("rate_limiter.requests.blocked")
                    .tag("strategy", strategyTag)
                    .register(meterRegistry)
                    .increment();
        }
    }
}
