package com.musicstreaming.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public final class RangeHeaderParser {

    private RangeHeaderParser() {
    }

    public static Optional<long[]> parseOptional(String rangeHeader, long fileSize) {
        try {
            String rangePart = rangeHeader.replace("bytes=", "").trim();
            String[] parts = rangePart.split("-");

            long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
            long end = parts.length > 1 && !parts[1].isEmpty()
                    ? Long.parseLong(parts[1])
                    : fileSize - 1;

            if (start > end || start >= fileSize || end >= fileSize) {
                return Optional.empty();
            }

            return Optional.of(new long[]{start, end});
        } catch (Exception e) {
            log.warn("Invalid range header: {}", rangeHeader);
            return Optional.empty();
        }
    }
}
