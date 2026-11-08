package com.kbconsole.tag;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_tag", uniqueConstraints = @UniqueConstraint(name = "uk_tag_series_name", columnNames = {"series_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class KbTag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, length = 16)
    private String color = "#1677ff";

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
