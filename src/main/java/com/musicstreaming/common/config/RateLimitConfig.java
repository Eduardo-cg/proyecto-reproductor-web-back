package com.musicstreaming.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private final RateLimitProperties properties;
    private final ConcurrentHashMap<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitConfig(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Bean
    public WebFilter rateLimitFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            String path = exchange.getRequest().getPath().value();

            if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
                int limit = path.equals("/api/auth/login")
                        ? properties.getLoginLimit()
                        : properties.getRegisterLimit();
                String key = path + ":" + getClientKey(exchange);

                if (!tryAcquire(key, limit)) {
                    return tooManyRequests(exchange);
                }
            }

            return chain.filter(exchange);
        };
    }

    private boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowMs = properties.getWindowMs();
        RateLimitBucket bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= windowMs) {
                return new RateLimitBucket(now);
            }
            return existing;
        });

        int count = bucket.counter.incrementAndGet();
        if (count > limit) {
            bucket.counter.decrementAndGet();
            return false;
        }
        return true;
    }

    private String getClientKey(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"error\":\"Too many requests. Please try again later.\"}".getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private static class RateLimitBucket {
        final AtomicInteger counter = new AtomicInteger(0);
        final long windowStart;

        RateLimitBucket(long windowStart) {
            this.windowStart = windowStart;
        }
    }
}
