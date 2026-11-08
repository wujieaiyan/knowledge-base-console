package com.kbconsole.member;

import com.kbconsole.common.Role;
import com.kbconsole.common.SubjectType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kb_member", uniqueConstraints = @UniqueConstraint(name = "uk_member_series_subject", columnNames = {"series_id", "subject_type", "subject_id"}))
@Getter
@Setter
@NoArgsConstructor
public class KbMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private Long seriesId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 10)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
