package com.musicstreaming.adapter.dto;

public class TrackUploadRequest {

    private String title;
    private String artist;
    private String album;
    private Integer duration;

    public TrackUploadRequest() {}

    public TrackUploadRequest(String title, String artist, String album, Integer duration) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public Integer getDuration() { return duration; }
    public void setDuration(Integer duration) { this.duration = duration; }
}