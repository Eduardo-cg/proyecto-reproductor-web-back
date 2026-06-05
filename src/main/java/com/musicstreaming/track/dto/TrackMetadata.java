package com.musicstreaming.track.dto;

import java.time.LocalDate;
import java.util.List;

public record TrackMetadata(
        String title,
        List<Long> artistIds,
        String album,
        Integer duration,
        Integer position,
        LocalDate releaseDate
) {
    public TrackMetadata {
        if (artistIds == null) {
            artistIds = List.of();
        }
    }
}
