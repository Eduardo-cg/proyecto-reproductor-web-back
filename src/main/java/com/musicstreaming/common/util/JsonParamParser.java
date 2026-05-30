package com.musicstreaming.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class JsonParamParser {

    private static final Logger log = LoggerFactory.getLogger(JsonParamParser.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonParamParser() {
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
}
