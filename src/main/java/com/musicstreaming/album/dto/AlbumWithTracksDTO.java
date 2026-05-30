package com.musicstreaming.album.dto;

import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.track.dto.TrackDTO;
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
public class AlbumWithTracksDTO {

    private Long id;
    private String title;
    private List<ArtistDTO> artists = new ArrayList<>();
    private String artist;
    private LocalDate releaseDate;
    private String cover;
    private Long userId;
    private int trackCount;
    private Long totalSize;
    private List<TrackDTO> tracks;

    public void setArtistsNames(List<ArtistDTO> artists) {
        this.artists = artists;
        if (artists != null && !artists.isEmpty()) {
            this.artist = artists.stream()
                    .map(ArtistDTO::getName)
                    .collect(Collectors.joining(", "));
        } else {
            this.artist = "";
        }
    }
}
