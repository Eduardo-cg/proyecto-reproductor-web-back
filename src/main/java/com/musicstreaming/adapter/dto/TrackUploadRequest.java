package com.musicstreaming.adapter.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TrackUploadRequest {

    private String title;
    private String artist;
    private String album;
    private Integer duration;
}