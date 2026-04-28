package com.example.ratelimiter.config;

import io.bucket4j.Bandwidth;
import io.bucket4j.BucketConfiguration;
import io.bucket4j.Refill;
import io.bucket4j.distributed.proxy.ProxyManager;
import io.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Configures Bucket4j rate limiter strategies backed by Redis.
 *
 * All buckets use LettuceBasedProxyManager so state is shared across
 * multiple service instances — enabling true distributed rate limiting.
 *
 * Strategies:
 *  - Standard   : 60 req/min with token bucket refill
 *  - Burst       : 60 req/min + 20 burst headroom
 *  - Strict      : 10 req/min, no burst
 *  - Sliding     : 500 req/hr via greedy refill
 *  - Daily API   : 10,000 req/day
 */
@Configuration
public class RateLimiterConfig {

    private final RateLimitProperties props;

    public RateLimiterConfig(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * Redis-backed ProxyManager. Every call to bucket(...) resolves the bucket
     * by a String key, storing state atomically in Redis via CAS operations.
     */
    @Bean
    public ProxyManager<String> proxyManager(RedisClient lettuceRedisClient) {
        StatefulRedisConnection<String, byte[]> connection =
                lettuceRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        return LettuceBasedProxyManager.builderFor(connection)
                .build();
    }

    // ── Bucket configuration suppliers ──────────────────────────────────────

    /**
     * Standard token bucket: refills 60 tokens every minute.
     * Good for most API endpoints.
     */
    @Bean
    public Supplier<BucketConfiguration> standardBucketConfig() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(
                        props.defaultConfig().requestsPerMinute(),
                        Refill.intervally(props.defaultConfig().requestsPerMinute(), Duration.ofMinutes(1))
                ))
                .build();
    }

    /**
     * Burst-tolerant bucket: allows short spikes up to (limit + burst)
     * while enforcing the sustained rate over time.
     */
    @Bean
    public Supplier<BucketConfiguration> burstBucketConfig() {
        int limit = props.defaultConfig().requestsPerMinute();
        int burst = props.defaultConfig().burstCapacity();
        return () -> BucketConfiguration.builder()
                // Sustained rate
                .addLimit(Bandwidth.classic(
                        limit,
                        Refill.greedy(limit, Duration.ofMinutes(1))
                ))
                // Burst headroom — tokens refill slowly to prevent abuse
                .addLimit(Bandwidth.classic(
                        limit + burst,
                        Refill.intervally(burst, Duration.ofSeconds(30))
                ))
                .build();
    }

    /**
     * Strict bucket: 10 req/min, no burst.
     * For sensitive endpoints (auth, payment, admin operations).
     */
    @Bean
    public Supplier<BucketConfiguration> strictBucketConfig() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(
                        props.strict().requestsPerMinute(),
                        Refill.intervally(props.strict().requestsPerMinute(), Duration.ofMinutes(1))
                ))
                .build();
    }

    /**
     * Sliding window emulation: 500 req/hr via greedy refill.
     * Greedy refill distributes tokens continuously, smoothing traffic spikes.
     */
    @Bean
    public Supplier<BucketConfiguration> slidingWindowBucketConfig() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(
                        props.slidingWindow().requestsPerHour(),
                        Refill.greedy(props.slidingWindow().requestsPerHour(), Duration.ofHours(1))
                ))
                .build();
    }

    /**
     * Daily API quota bucket: 10,000 req/day.
     * Resets once per day — useful for external API consumers / tenant quotas.
     */
    @Bean
    public Supplier<BucketConfiguration> dailyApiBucketConfig() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.classic(
                        props.api().requestsPerDay(),
                        Refill.intervally(props.api().requestsPerDay(), Duration.ofDays(1))
                ))
                .build();
    }
}
