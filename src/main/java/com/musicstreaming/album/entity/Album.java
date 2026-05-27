package com.musicstreaming.album.entity;

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
@Table("albums")
public class Album {

    @Id
    private Long id;

    @Column("title")
    private String title;

    @Column("release_date")
    private LocalDate releaseDate;

    @Column("cover_path")
    private String coverPath;

    @Column("user_id")
    private Long userId;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;
}
