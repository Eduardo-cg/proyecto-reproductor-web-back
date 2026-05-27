package com.musicstreaming.album.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Table("album_artists")
public class AlbumArtist {

    @Id
    private Long id;

    @Column("album_id")
    private Long albumId;

    @Column("artist_id")
    private Long artistId;

    @Column("position")
    private Integer position;

    @Column("created_at")
    private LocalDateTime createdAt;
}
