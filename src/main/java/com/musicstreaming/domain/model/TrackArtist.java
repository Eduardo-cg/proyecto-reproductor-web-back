package com.musicstreaming.domain.model;

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
@Table("track_artists")
public class TrackArtist {

    @Id
    private Long id;

    @Column("track_id")
    private Long trackId;

    @Column("artist_id")
    private Long artistId;

    @Column("position")
    private Integer position;

    @Column("created_at")
    private LocalDateTime createdAt;
}
