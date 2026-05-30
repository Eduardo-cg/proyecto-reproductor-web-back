package com.musicstreaming.track.dto;

import com.musicstreaming.artist.dto.ArtistDTO;
import com.musicstreaming.track.entity.Track;
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
public class TrackDTO {

    private Long id;
    private String title;
    private List<ArtistDTO> artists = new ArrayList<>();
    private String artist;
    private String album;
    private Integer duration;
    private Long fileSize;
    private String cover;
    private Long userId;
    private LocalDate releaseDate;

    public static TrackDTO fromEntity(Track track) {
        TrackDTO dto = new TrackDTO();
        dto.id = track.getId();
        dto.title = track.getTitle();
        dto.album = track.getAlbum();
        dto.duration = track.getDuration();
        dto.fileSize = track.getFileSize();
        dto.userId = track.getUserId();
        dto.releaseDate = track.getReleaseDate();
        return dto;
    }

    public static TrackDTO fromEntity(Track track, String coverBase64) {
        TrackDTO dto = fromEntity(track);
        dto.cover = coverBase64;
        return dto;
    }

    public void setArtists(List<ArtistDTO> artists) {
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
