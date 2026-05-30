package com.musicstreaming.common.util;

import java.time.LocalDate;

public final class ParserUtils {

    private ParserUtils() {
    }

    public static LocalDate parseOptionalDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        return LocalDate.parse(dateStr);
    }

    public static Integer parseOptionalInt(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return Integer.valueOf(value);
    }
}
