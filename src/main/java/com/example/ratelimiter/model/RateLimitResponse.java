package com.example.ratelimiter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Unified API response for rate-limited endpoints.
 * Includes remaining token count and retry-after seconds when blocked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RateLimitResponse(
        String status,
        String message,
        long remainingTokens,
        Long retryAfterSeconds,
        String strategy,
        String bucketKey
) {
    public static RateLimitResponse allowed(long remaining, String strategy, String key) {
        return new RateLimitResponse("ALLOWED", "Request processed successfully",
                remaining, null, strategy, key);
    }

    public static RateLimitResponse blocked(long retryAfter, String strategy, String key) {
        return new RateLimitResponse("RATE_LIMITED",
                "Too Many Requests — retry after " + retryAfter + "s",
                0, retryAfter, strategy, key);
    }
}
