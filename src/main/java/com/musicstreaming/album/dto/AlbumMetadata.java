package com.musicstreaming.album.dto;

import java.time.LocalDate;
import java.util.List;

public record AlbumMetadata(
        String title,
        List<Long> artistIds,
        LocalDate releaseDate
) {
    public AlbumMetadata {
        if (artistIds == null) {
            artistIds = List.of();
        }
    }
}
