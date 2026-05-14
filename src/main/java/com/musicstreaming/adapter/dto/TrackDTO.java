package com.musicstreaming.adapter.dto;

import com.musicstreaming.domain.model.Track;

public class TrackDTO {

    private Long id;
    private String title;
    private String artist;
    private String album;
    private Integer duration;
    private String mimeType;
    private Long fileSize;

    public TrackDTO() {}

    public TrackDTO(Long id, String title, String artist, String album,
                    Integer duration, String mimeType, Long fileSize) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }

    public static TrackDTO fromEntity(Track track) {
        return new TrackDTO(
                track.getId(),
                track.getTitle(),
                track.getArtist(),
                track.getAlbum(),
                track.getDuration(),
                track.getMimeType(),
                track.getFileSize()
        );
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
}