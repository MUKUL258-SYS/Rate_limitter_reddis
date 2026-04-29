package com.example.ratelimiter.aspect;

import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.model.RateLimitResponse;
import com.example.ratelimiter.model.RateLimitStrategy;
import com.example.ratelimiter.service.RateLimiterService;
import io.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * ================================================================
 * RateLimitAspect
 * ================================================================
 *
 * This class is an AOP (Aspect Oriented Programming) aspect.
 *
 * Instead of writing rate-limiting logic inside every controller,
 * this aspect automatically intercepts methods that contain
 * the @RateLimit annotation.
 *
 * Example:
 *
 * @RateLimit(strategy = RateLimitStrategy.BASIC)
 * public ResponseEntity<String> getData() { ... }
 *
 * Before the actual controller method executes:
 *  -> this aspect runs first
 *  -> checks whether request is allowed
 *  -> if limit exceeded -> blocks request
 *  -> if allowed -> controller method executes normally
 *
 * ================================================================
 * Flow:
 * ================================================================
 *
 * Client Request
 *      ↓
 * Spring Controller Method
 *      ↓
 * @RateLimit annotation detected
 *      ↓
 * RateLimitAspect intercepts request
 *      ↓
 * Resolve user/IP key
 *      ↓
 * Check Redis/Bucket4j token bucket
 *      ↓
 * Allowed? ---- YES ---> execute actual API method
 *      |
 *      NO
 *      ↓
 * Return HTTP 429 (Too Many Requests)
 *
 * ================================================================
 * Why AOP is useful here?
 * ================================================================
 *
 * Without AOP:
 * every controller would need repeated rate-limit code.
 *
 * With AOP:
 * reusable centralized logic.
 *
 * ================================================================
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /**
     * Service responsible for:
     * - communicating with Redis/Bucket4j
     * - checking token availability
     * - calculating retry time
     */
    private final RateLimiterService rateLimiterService;

    /**
     * ============================================================
     * @Around Advice
     * ============================================================
     *
     * This method runs BEFORE and AFTER methods annotated
     * with @RateLimit.
     *
     * Important:
     * pjp.proceed() -> executes the actual controller method
     *
     * If we DO NOT call proceed():
     * the real API method never executes.
     *
     * ============================================================
     */
    @Around("@annotation(com.example.ratelimiter.annotation.RateLimit)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {

        /**
         * Extract method information from intercepted method.
         *
         * Example intercepted method:
         *
         * @RateLimit(...)
         * public String getUsers()
         *
         * We use reflection to access annotation details.
         */
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        /**
         * Get @RateLimit annotation values from method.
         *
         * Example:
         * strategy = BASIC
         * perUser = true
         * keyPrefix = "login"
         */
        RateLimit annotation = method.getAnnotation(RateLimit.class);

        /**
         * Extract rate limiting strategy.
         *
         * Example:
         * BASIC
         * PREMIUM
         * LOGIN_LIMIT
         */
        RateLimitStrategy strategy = annotation.strategy();

        /**
         * Resolve unique client identity.
         *
         * Could be:
         * - user id
         * - client IP
         */
        String clientKey = resolveClientKey(annotation);

        /**
         * Final Redis/Bucket key.
         *
         * Example:
         * login:192.168.1.10
         * api:user123
         */
        String bucketKey = annotation.keyPrefix() + ":" + clientKey;

        /**
         * Ask rate limiter service:
         * "Can this request consume 1 token?"
         *
         * Bucket4j returns ConsumptionProbe containing:
         * - whether request allowed
         * - remaining tokens
         * - wait time if blocked
         */
        ConsumptionProbe probe =
                rateLimiterService.tryConsume(strategy, bucketKey);

        /**
         * Get HTTP response object
         * so we can attach headers.
         */
        HttpServletResponse response = getResponse();

        /**
         * ========================================================
         * CASE 1 -> Request Allowed
         * ========================================================
         */
        if (probe.isConsumed()) {

            /**
             * Add helpful rate-limit headers.
             *
             * Example:
             * X-RateLimit-Remaining: 5
             * X-RateLimit-Strategy: BASIC
             */
            if (response != null) {

                response.addHeader(
                        "X-RateLimit-Remaining",
                        String.valueOf(probe.getRemainingTokens())
                );

                response.addHeader(
                        "X-RateLimit-Strategy",
                        strategy.name()
                );
            }

            /**
             * IMPORTANT:
             * Execute ACTUAL controller method.
             *
             * Without this line:
             * controller method never runs.
             */
            return pjp.proceed();
        }

        /**
         * ========================================================
         * CASE 2 -> Request Blocked
         * ========================================================
         *
         * We DO NOT execute actual API method.
         */

        /**
         * Calculate how long client should wait
         * before retrying request.
         */
        long retryAfter =
                rateLimiterService.retryAfterSeconds(probe);

        /**
         * Add retry information to response headers.
         */
        if (response != null) {

            response.addHeader(
                    "Retry-After",
                    String.valueOf(retryAfter)
            );

            response.addHeader(
                    "X-RateLimit-Strategy",
                    strategy.name()
            );
        }

        /**
         * Log blocked request for monitoring/debugging.
         */
        log.warn(
                "Rate limit exceeded — strategy={} key={} retryAfter={}s",
                strategy,
                bucketKey,
                retryAfter
        );

        /**
         * Return HTTP 429 response immediately.
         *
         * IMPORTANT:
         * controller method never executes here.
         */
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(
                        RateLimitResponse.blocked(
                                retryAfter,
                                strategy.name(),
                                bucketKey
                        )
                );
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    /**
     * Resolve unique client identifier.
     *
     * If perUser=true:
     *  -> use X-User-Id header
     *
     * Otherwise:
     *  -> use IP address
     */
    private String resolveClientKey(RateLimit annotation) {

        HttpServletRequest request = getRequest();

        if (request == null) {
            return "unknown";
        }

        /**
         * User-based rate limiting.
         */
        if (annotation.perUser()) {

            /**
             * Example:
             * X-User-Id: user123
             */
            String userId =
                    request.getHeader("X-User-Id");

            /**
             * If userId missing,
             * fallback to IP address.
             */
            return userId != null
                    ? userId
                    : resolveIp(request);
        }

        /**
         * IP-based rate limiting.
         */
        return resolveIp(request);
    }

    /**
     * ============================================================
     * Resolve Real Client IP
     * ============================================================
     *
     * Why needed?
     *
     * In production:
     * requests often go through:
     *
     * Client
     *   ↓
     * Load Balancer / Nginx
     *   ↓
     * Spring Boot App
     *
     * request.getRemoteAddr()
     * may return proxy IP instead of real client IP.
     *
     * So we first check:
     * X-Forwarded-For header
     *
     * Example:
     * X-Forwarded-For:
     * 203.0.113.1, 10.0.0.2
     *
     * First IP = real client.
     */
    private String resolveIp(HttpServletRequest request) {

        String forwarded =
                request.getHeader("X-Forwarded-For");

        if (forwarded != null && !forwarded.isBlank()) {

            /**
             * Extract first IP from chain.
             */
            return forwarded.split(",")[0].trim();
        }

        /**
         * Fallback:
         * direct remote address.
         */
        return request.getRemoteAddr();
    }

    /**
     * Get current HTTP request object
     * from Spring request context.
     */
    private HttpServletRequest getRequest() {

        try {

            ServletRequestAttributes attrs =
                    (ServletRequestAttributes)
                            RequestContextHolder
                                    .currentRequestAttributes();

            return attrs.getRequest();

        } catch (IllegalStateException e) {

            /**
             * Happens if no active HTTP request exists.
             */
            return null;
        }
    }

    /**
     * Get current HTTP response object
     * from Spring request context.
     */
    private HttpServletResponse getResponse() {

        try {

            ServletRequestAttributes attrs =
                    (ServletRequestAttributes)
                            RequestContextHolder
                                    .currentRequestAttributes();

            return attrs.getResponse();

        } catch (IllegalStateException e) {

            return null;
        }
    }
}
