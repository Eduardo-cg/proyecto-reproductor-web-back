package com.musicstreaming.auth.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Getter
@Setter
@NoArgsConstructor
@Table("roles")
public class Role {

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("storage_limit_bytes")
    private Long storageLimitBytes;
}
