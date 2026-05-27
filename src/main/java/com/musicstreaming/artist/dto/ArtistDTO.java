package com.musicstreaming.artist.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArtistDTO {

    private Long id;
    private String name;
    private String image;
    private Long userId;
    private int trackCount;
    private int albumCount;
}
