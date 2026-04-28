package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding for rate limit configuration.
 * Values are externalized and overridable via env vars or Spring Cloud Config.
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        Default defaultConfig,
        Strict strict,
        Api api,
        SlidingWindow slidingWindow
) {
    public record Default(int requestsPerMinute, int burstCapacity) {}
    public record Strict(int requestsPerMinute) {}
    public record Api(int requestsPerDay) {}
    public record SlidingWindow(int requestsPerHour) {}
}
