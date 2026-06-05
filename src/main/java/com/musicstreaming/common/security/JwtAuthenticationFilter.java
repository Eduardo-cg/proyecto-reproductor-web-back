package com.musicstreaming.common.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter implements WebFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String STREAM_SUFFIX = "/stream";

    private final JwtTokenProvider tokenProvider;
    private final ReactiveUserDetailsService userDetailsService;

    @Override
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {

        String token = getTokenFromRequest(exchange);

        if (token != null && tokenProvider.validateToken(token)) {
            String username = tokenProvider.getUsernameFromToken(token);

            return userDetailsService.findByUsername(username)
                    .flatMap(userDetails -> {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails,
                                        null,
                                        userDetails.getAuthorities()
                                );
                        return chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                    })
                    .onErrorResume(UsernameNotFoundException.class, e -> {
                        log.debug("User from token not found: {}", username);
                        return chain.filter(exchange);
                    })
                    .onErrorResume(e -> {
                        if (isInfrastructureError(e)) {
                            log.warn("Infrastructure error during authentication for user {}: {}", username, e.getMessage());
                        } else {
                            log.error("Error authenticating user {}: {}", username, e.getMessage());
                        }
                        return chain.filter(exchange);
                    });
        }

        return chain.filter(exchange);
    }

    private boolean isInfrastructureError(Throwable e) {
        String name = e.getClass().getName();
        return name.contains("R2dbc")
                || name.contains("DataAccess")
                || name.contains("Timeout")
                || name.contains("Connection");
    }

    private String getTokenFromRequest(ServerWebExchange exchange) {
        String bearerToken = exchange.getRequest()
                .getHeaders()
                .getFirst(AUTHORIZATION_HEADER);

        if (bearerToken != null && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }

        String path = exchange.getRequest().getPath().value();
        if (path.endsWith(STREAM_SUFFIX)) {
            String queryToken = exchange.getRequest().getQueryParams().getFirst("token");
            if (queryToken != null && !queryToken.isEmpty()) {
                return queryToken;
            }
        }

        return null;
    }
}
