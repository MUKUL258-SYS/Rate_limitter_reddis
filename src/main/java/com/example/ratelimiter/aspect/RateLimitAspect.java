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
 * AOP aspect that intercepts methods annotated with {@link RateLimit}
 * and enforces distributed rate limiting before allowing execution.
 *
 * Client key resolution order:
 *  1. If perUser=true  → X-User-Id header
 *  2. If perUser=false → X-Forwarded-For header (proxy-aware IP)
 *  3. Fallback         → remote address
 *
 * On rate limit exceeded, the aspect short-circuits the method call and
 * returns HTTP 429 with Retry-After header — the real method never executes.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiterService rateLimiterService;

    @Around("@annotation(com.example.ratelimiter.annotation.RateLimit)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        RateLimit annotation = method.getAnnotation(RateLimit.class);

        RateLimitStrategy strategy = annotation.strategy();
        String clientKey = resolveClientKey(annotation);
        String bucketKey = annotation.keyPrefix() + ":" + clientKey;

        ConsumptionProbe probe = rateLimiterService.tryConsume(strategy, bucketKey);

        HttpServletResponse response = getResponse();

        if (probe.isConsumed()) {
            // Add informational headers — let the real method proceed
            if (response != null) {
                response.addHeader("X-RateLimit-Remaining",
                        String.valueOf(probe.getRemainingTokens()));
                response.addHeader("X-RateLimit-Strategy", strategy.name());
            }
            return pjp.proceed();
        }

        // Short-circuit: return 429 without executing the method
        long retryAfter = rateLimiterService.retryAfterSeconds(probe);
        if (response != null) {
            response.addHeader("Retry-After", String.valueOf(retryAfter));
            response.addHeader("X-RateLimit-Strategy", strategy.name());
        }

        log.warn("Rate limit exceeded — strategy={} key={} retryAfter={}s",
                strategy, bucketKey, retryAfter);

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(RateLimitResponse.blocked(retryAfter, strategy.name(), bucketKey));
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private String resolveClientKey(RateLimit annotation) {
        HttpServletRequest request = getRequest();
        if (request == null) return "unknown";

        if (annotation.perUser()) {
            String userId = request.getHeader("X-User-Id");
            return userId != null ? userId : resolveIp(request);
        }
        return resolveIp(request);
    }

    /**
     * Resolves real client IP, handling reverse proxy forwarding.
     * Checks X-Forwarded-For first (set by nginx/load balancer),
     * falls back to request.getRemoteAddr().
     */
    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For can be a comma-separated chain; first IP is the real client
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest getRequest() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private HttpServletResponse getResponse() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getResponse();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
