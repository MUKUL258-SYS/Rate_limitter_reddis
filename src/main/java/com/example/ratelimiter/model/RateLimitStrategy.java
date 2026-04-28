package com.example.ratelimiter.model;

/**
 * Available rate limiting strategies.
 * Each maps to a BucketConfiguration bean in RateLimiterConfig.
 */
public enum RateLimitStrategy {
    STANDARD,   // 60 req/min token bucket
    BURST,      // 60 req/min + burst headroom
    STRICT,     // 10 req/min, no burst (auth, payments)
    SLIDING,    // 500 req/hr greedy refill
    DAILY       // 10,000 req/day quota
}
