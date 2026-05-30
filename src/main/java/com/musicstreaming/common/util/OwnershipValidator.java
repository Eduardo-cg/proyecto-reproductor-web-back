package com.musicstreaming.common.util;

import com.musicstreaming.common.exception.ResourceNotFoundException;
import com.musicstreaming.common.exception.UnauthorizedException;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

public final class OwnershipValidator {

    private OwnershipValidator() {
    }

    public static <T> Mono<T> requireOwnership(Mono<T> entityMono, Long userId, String entityType, Long entityId, Function<T, Long> userIdExtractor) {
        return entityMono
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(entityType, entityId)))
                .flatMap(entity -> {
                    Long entityUserId = userIdExtractor.apply(entity);
                    if (!Objects.equals(entityUserId, userId)) {
                        return Mono.error(new UnauthorizedException("Access denied to " + entityType.toLowerCase()));
                    }
                    return Mono.just(entity);
                });
    }
}
