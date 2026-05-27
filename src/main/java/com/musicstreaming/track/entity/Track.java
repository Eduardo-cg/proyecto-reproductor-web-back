package com.musicstreaming.track.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Table("tracks")
public class Track {

    @Id
    private Long id;

    @Column("title")
    private String title;

    @Column("album")
    private String album;

    @Column("duration")
    private Integer duration;

    @Column("file_path")
    private String filePath;

    @Column("cover_path")
    private String coverPath;

    @Column("user_id")
    private Long userId;

    @Column("release_date")
    private LocalDate releaseDate;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}