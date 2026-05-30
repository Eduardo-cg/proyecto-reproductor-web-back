package com.musicstreaming.auth.service;

import com.musicstreaming.auth.dto.StorageUsageResponse;
import com.musicstreaming.auth.entity.Role;
import com.musicstreaming.auth.repository.RoleRepository;
import com.musicstreaming.auth.repository.UserRepository;
import com.musicstreaming.common.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Paths;

@Service
public class StorageService {

    private static final long ADMIN_ROLE_ID = 1L;

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
                        .flatMap(role -> userRepository.sumTrackFileSizeByUserId(userId)
                                .map(usedBytes -> {
                                    if (user.getRoleId().equals(ADMIN_ROLE_ID)) {
                                        long diskTotal = getDiskTotalSpace();
                                        long diskAvailable = getDiskUsableSpace();
                                        long available = Math.max(0, diskAvailable - usedBytes);
                                        return new StorageUsageResponse(usedBytes, diskTotal, available, "ADMIN");
                                    } else {
                                        long limit = role.getStorageLimitBytes() != null ? role.getStorageLimitBytes() : 536870912L;
                                        long available = Math.max(0, limit - usedBytes);
                                        return new StorageUsageResponse(usedBytes, limit, available, "STANDARD");
                                    }
                                })));
    }

    public Mono<Boolean> checkLimit(Long userId, Long additionalBytes) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("User", userId)))
                .flatMap(user -> roleRepository.findById(user.getRoleId())
                        .flatMap(role -> {
                            if (user.getRoleId().equals(ADMIN_ROLE_ID)) {
                                long diskAvailable = getDiskUsableSpace();
                                return userRepository.sumTrackFileSizeByUserId(userId)
                                        .map(usedBytes -> (usedBytes + additionalBytes) <= diskAvailable);
                            }
                            long limit = role.getStorageLimitBytes() != null ? role.getStorageLimitBytes() : 536870912L;
                            return userRepository.sumTrackFileSizeByUserId(userId)
                                    .map(usedBytes -> (usedBytes + additionalBytes) <= limit);
                        }));
    }

    private long getDiskTotalSpace() {
        try {
            FileStore store = FileSystems.getDefault().provider().getFileStore(Paths.get(storageBasePath));
            return store.getTotalSpace();
        } catch (Exception e) {
            return 0L;
        }
    }

    private long getDiskUsableSpace() {
        try {
            FileStore store = FileSystems.getDefault().provider().getFileStore(Paths.get(storageBasePath));
            return store.getUsableSpace();
        } catch (Exception e) {
            return 0L;
        }
    }
}
