package com.musicstreaming.adapter.dto;

import com.musicstreaming.domain.model.Playlist;
import com.musicstreaming.domain.model.Track;

import java.time.LocalDateTime;
import java.util.List;

public class PlaylistDTO {

    private Long id;
    private Long userId;
    private String name;
    private String description;
    private Boolean isPublic;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<TrackDTO> tracks;

    public PlaylistDTO() {}

    public PlaylistDTO(Long id, Long userId, String name, String description,
                       Boolean isPublic, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.description = description;
        this.isPublic = isPublic;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PlaylistDTO fromEntity(Playlist playlist) {
        return new PlaylistDTO(
                playlist.getId(),
                playlist.getUserId(),
                playlist.getName(),
                playlist.getDescription(),
                playlist.getIsPublic(),
                playlist.getCreatedAt(),
                playlist.getUpdatedAt()
        );
    }

    public PlaylistDTO withTracks(List<TrackDTO> tracks) {
        this.tracks = tracks;
        return this;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<TrackDTO> getTracks() { return tracks; }
    public void setTracks(List<TrackDTO> tracks) { this.tracks = tracks; }
}