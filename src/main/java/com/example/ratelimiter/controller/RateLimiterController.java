package com.example.ratelimiter.controller;

import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.model.RateLimitStrategy;
import com.example.ratelimiter.service.RateLimiterService;
import io.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller demonstrating all rate limiting strategies.
 *
 * Two usage patterns:
 *  1. AOP-based   : annotate the method with @RateLimit — zero boilerplate in method body
 *  2. Programmatic: inject RateLimiterService for fine-grained control (e.g., conditional logic)
 */
@RestController
@RequestMapping("/api/v1/rate-limiter")
@RequiredArgsConstructor
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;

    // ── AOP-based endpoints (annotation-driven) ──────────────────────────────

    /**
     * Standard endpoint: 60 req/min per IP.
     * Rate limiting handled entirely by AOP — no manual bucket logic.
     */
    @GetMapping("/standard")
    @RateLimit(strategy = RateLimitStrategy.STANDARD, keyPrefix = "standard")
    public ResponseEntity<Map<String, String>> standard() {
        return ResponseEntity.ok(Map.of(
                "message", "Standard rate limit — 60 req/min",
                "status", "OK"
        ));
    }

    /**
     * Burst endpoint: sustained 60 req/min, short burst up to 80.
     * Use for high-traffic public endpoints with natural bursts (e.g., search).
     */
    @GetMapping("/burst")
    @RateLimit(strategy = RateLimitStrategy.BURST, keyPrefix = "burst")
    public ResponseEntity<Map<String, String>> burst() {
        return ResponseEntity.ok(Map.of(
                "message", "Burst-tolerant — 60/min sustained, 80 burst",
                "status", "OK"
        ));
    }

    /**
     * Strict endpoint: 10 req/min per IP.
     * For sensitive operations — auth, OTP, password reset.
     */
    @PostMapping("/auth/login")
    @RateLimit(strategy = RateLimitStrategy.STRICT, keyPrefix = "auth-login")
    public ResponseEntity<Map<String, String>> login(@RequestBody(required = false) Object body) {
        return ResponseEntity.ok(Map.of(
                "message", "Authentication endpoint — strictly rate limited",
                "status", "OK"
        ));
    }

    /**
     * Sliding window endpoint: 500 req/hr, smooth distribution.
     */
    @GetMapping("/sliding")
    @RateLimit(strategy = RateLimitStrategy.SLIDING, keyPrefix = "sliding")
    public ResponseEntity<Map<String, String>> sliding() {
        return ResponseEntity.ok(Map.of(
                "message", "Sliding window — 500 req/hr",
                "status", "OK"
        ));
    }

    /**
     * Per-user endpoint: limits are tracked per X-User-Id header.
     * Each user gets an independent 60 req/min bucket in Redis.
     */
    @GetMapping("/user/profile")
    @RateLimit(strategy = RateLimitStrategy.STANDARD, keyPrefix = "user-profile", perUser = true)
    public ResponseEntity<Map<String, String>> userProfile(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "message", "Per-user rate limited endpoint",
                "status", "OK"
        ));
    }

    // ── Programmatic endpoint (manual bucket logic) ──────────────────────────

    /**
     * Daily API quota: 10,000 req/day per API key.
     * Uses programmatic approach to demonstrate manual bucket consumption,
     * useful when rate limit behavior needs to vary based on request content.
     */
    @GetMapping("/api-quota")
    public ResponseEntity<RateLimitResponse> apiQuota(
            @RequestHeader(value = "X-Api-Key", defaultValue = "anonymous") String apiKey,
            HttpServletResponse response) {

        ConsumptionProbe probe = rateLimiterService.tryConsume(RateLimitStrategy.DAILY, apiKey);

        if (probe.isConsumed()) {
            response.addHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return ResponseEntity.ok(
                    RateLimitResponse.allowed(probe.getRemainingTokens(), "DAILY", apiKey));
        }

        long retryAfter = rateLimiterService.retryAfterSeconds(probe);
        response.addHeader("Retry-After", String.valueOf(retryAfter));
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(RateLimitResponse.blocked(retryAfter, "DAILY", apiKey));
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    /**
     * Resets the rate limit bucket for a given user. Admin use only.
     * In production, this should be secured behind an admin role.
     */
    @DeleteMapping("/admin/reset/{strategy}/{clientKey}")
    public ResponseEntity<Map<String, String>> resetBucket(
            @PathVariable String strategy,
            @PathVariable String clientKey) {
        RateLimitStrategy s = RateLimitStrategy.valueOf(strategy.toUpperCase());
        rateLimiterService.resetBucket(s, clientKey);
        return ResponseEntity.ok(Map.of(
                "message", "Bucket reset for " + strategy + ":" + clientKey,
                "status", "OK"
        ));
    }

    /**
     * Health check endpoint — not rate limited.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "rate-limiter"));
    }
}
