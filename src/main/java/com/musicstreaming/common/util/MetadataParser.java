package com.musicstreaming.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicstreaming.common.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class MetadataParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MetadataParser() {
    }

    public static List<Long> parseLongList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse long list from JSON: {}", json);
            return new ArrayList<>();
        }
    }

    public static LocalDate parseOptionalDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid date format: " + dateStr);
        }
    }

    public static Integer parseOptionalInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid number format: " + value);
        }
    }

    public static int parseRequiredInt(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new BadRequestException("Field '" + fieldName + "' is required");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Field '" + fieldName + "' must be a valid integer: " + value);
        }
    }
}
