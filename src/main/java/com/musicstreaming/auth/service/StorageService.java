package com.musicstreaming.auth.service;

import com.musicstreaming.auth.dto.StorageUsageResponse;
import com.musicstreaming.auth.repository.RoleRepository;
import com.musicstreaming.auth.repository.UserRepository;
import com.musicstreaming.common.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Paths;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final long ADMIN_ROLE_ID = 1L;
    private static final long DEFAULT_STANDARD_LIMIT_BYTES = 512L * 1024 * 1024;
    private static final String ADMIN_ROLE_NAME = "ADMIN";
    private static final String STANDARD_ROLE_NAME = "STANDARD";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Value("${app.storage.base-path}")
    private String storageBasePath;

    public StorageService(UserRepository userRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    public Mono<StorageUsageResponse> getUsage(Long userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", userId)))
                .flatMap(user -> roleRepository.findById(user.getRoleId())
                        .flatMap(role -> Mono.zip(
                                userRepository.sumTrackFileSizeByUserId(userId),
                                getDiskTotalSpace(),
                                getDiskUsableSpace()
                        )
                        .map(tuple -> {
                            long usedBytes = tuple.getT1();
                            if (user.getRoleId().equals(ADMIN_ROLE_ID)) {
                                long diskTotal = tuple.getT2();
                                long diskAvailable = tuple.getT3();
                                long available = Math.max(0, diskAvailable - usedBytes);
                                return new StorageUsageResponse(usedBytes, diskTotal, available, ADMIN_ROLE_NAME);
                            } else {
                                long limit = role.getStorageLimitBytes() != null ? role.getStorageLimitBytes() : DEFAULT_STANDARD_LIMIT_BYTES;
                                long available = Math.max(0, limit - usedBytes);
                                return new StorageUsageResponse(usedBytes, limit, available, STANDARD_ROLE_NAME);
                            }
                        })));
    }

    public Mono<Boolean> checkLimit(Long userId, Long additionalBytes) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", userId)))
                .flatMap(user -> roleRepository.findById(user.getRoleId())
                        .flatMap(role -> {
                            if (user.getRoleId().equals(ADMIN_ROLE_ID)) {
                                return getDiskUsableSpace()
                                        .flatMap(diskAvailable -> userRepository.sumTrackFileSizeByUserId(userId)
                                                .map(usedBytes -> (usedBytes + additionalBytes) <= diskAvailable));
                            }
                            long limit = role.getStorageLimitBytes() != null ? role.getStorageLimitBytes() : DEFAULT_STANDARD_LIMIT_BYTES;
                            return userRepository.sumTrackFileSizeByUserId(userId)
                                    .map(usedBytes -> (usedBytes + additionalBytes) <= limit);
                        }));
    }

    private Mono<Long> getDiskTotalSpace() {
        return Mono.fromCallable(() -> {
                    FileStore store = FileSystems.getDefault().provider().getFileStore(Paths.get(storageBasePath));
                    return store.getTotalSpace();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to read total disk space for {}: {}", storageBasePath, e.getMessage());
                    return Mono.just(0L);
                });
    }

    private Mono<Long> getDiskUsableSpace() {
        return Mono.fromCallable(() -> {
                    FileStore store = FileSystems.getDefault().provider().getFileStore(Paths.get(storageBasePath));
                    return store.getUsableSpace();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Failed to read usable disk space for {}: {}", storageBasePath, e.getMessage());
                    return Mono.just(0L);
                });
    }
}
