package com.musicstreaming.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StorageUsageResponse {

    private Long usedBytes;
    private Long limitBytes;
    private Long availableBytes;
    private String roleName;
}
