package com.musicstreaming.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AlbumDTO {

    private Long id;
    private String title;
    private List<ArtistDTO> artists = new ArrayList<>();
    private String artistDisplay;
    private LocalDate releaseDate;
    private String cover;
    private Long userId;
    private int trackCount;

    public void setArtists(List<ArtistDTO> artists) {
        this.artists = artists;
        if (artists != null && !artists.isEmpty()) {
            this.artistDisplay = artists.stream()
                    .map(ArtistDTO::getName)
                    .collect(Collectors.joining(", "));
        } else {
            this.artistDisplay = "";
        }
    }
}
