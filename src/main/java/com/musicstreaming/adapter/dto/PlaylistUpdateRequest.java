package com.musicstreaming.adapter.dto;

import jakarta.validation.constraints.Size;

public class PlaylistUpdateRequest {

    @Size(max = 255, message = "Name must not exceed 255 characters")
    private String name;

    private String description;

    private Boolean isPublic;

    public PlaylistUpdateRequest() {}

    public PlaylistUpdateRequest(String name, String description, Boolean isPublic) {
        this.name = name;
        this.description = description;
        this.isPublic = isPublic;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }
}