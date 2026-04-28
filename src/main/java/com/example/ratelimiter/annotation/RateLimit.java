package com.example.ratelimiter.annotation;

import com.example.ratelimiter.model.RateLimitStrategy;

import java.lang.annotation.*;

/**
 * Marks a controller method for AOP-driven rate limiting.
 *
 * Usage:
 *   {@code @RateLimit(strategy = RateLimitStrategy.STRICT, keyPrefix = "auth")}
 *
 * The AOP aspect resolves the bucket key as: {keyPrefix}:{clientIp}
 * If perUser=true, key becomes: {keyPrefix}:{userId}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    RateLimitStrategy strategy() default RateLimitStrategy.STANDARD;
    String keyPrefix() default "default";
    boolean perUser() default false;
}
