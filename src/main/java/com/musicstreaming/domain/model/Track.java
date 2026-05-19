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
@Table("tracks")
public class Track {

    @Id
    private Long id;

    @Column("title")
    private String title;

    @Column("artist")
    private String artist;

    @Column("album")
    private String album;

    @Column("duration")
    private Integer duration;

    @Column("file_path")
    private String filePath;

    @Column("mime_type")
    private String mimeType;

    @Column("file_size")
    private Long fileSize;

    @Column("cover_path")
    private String coverPath;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    public Track(String title, String artist, String album, Integer duration,
                 String filePath, String mimeType, Long fileSize) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
    }
}