package com.musicstreaming.adapter.dto;

import com.musicstreaming.domain.model.Track;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TrackDTO {

    private Long id;
    private String title;
    private String artist;
    private String album;
    private Integer duration;
    private String mimeType;
    private Long fileSize;
    private String cover;

    public static TrackDTO fromEntity(Track track) {
        TrackDTO dto = new TrackDTO();
        dto.id = track.getId();
        dto.title = track.getTitle();
        dto.artist = track.getArtist();
        dto.album = track.getAlbum();
        dto.duration = track.getDuration();
        dto.mimeType = track.getMimeType();
        dto.fileSize = track.getFileSize();
        return dto;
    }

    public static TrackDTO fromEntity(Track track, String coverBase64) {
        TrackDTO dto = fromEntity(track);
        dto.cover = coverBase64;
        return dto;
    }
}