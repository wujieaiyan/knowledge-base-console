package com.kbconsole.member;

import com.kbconsole.common.Role;
import com.kbconsole.common.SubjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface KbMemberRepository extends JpaRepository<KbMember, Long> {
    List<KbMember> findBySeriesIdOrderByCreatedAtAsc(Long seriesId);

    Optional<KbMember> findBySeriesIdAndSubjectTypeAndSubjectId(Long seriesId, SubjectType subjectType, Long subjectId);

    Optional<KbMember> findByIdAndSeriesId(Long id, Long seriesId);

    List<KbMember> findBySubjectTypeAndSubjectId(SubjectType subjectType, Long subjectId);

    long countBySeriesIdAndRoleIn(Long seriesId, Collection<Role> roles);

    void deleteBySeriesId(Long seriesId);
}
