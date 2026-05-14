package com.musicstreaming.domain.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("playlist_tracks")
public class PlaylistTrack {

    @Id
    private Long id;

    @Column("playlist_id")
    private Long playlistId;

    @Column("track_id")
    private Long trackId;

    @Column("position")
    private Integer position;

    @Column("added_at")
    private LocalDateTime addedAt;

    public PlaylistTrack() {}

    public PlaylistTrack(Long playlistId, Long trackId, Integer position) {
        this.playlistId = playlistId;
        this.trackId = trackId;
        this.position = position;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPlaylistId() { return playlistId; }
    public void setPlaylistId(Long playlistId) { this.playlistId = playlistId; }

    public Long getTrackId() { return trackId; }
    public void setTrackId(Long trackId) { this.trackId = trackId; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }
}