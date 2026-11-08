package com.kbconsole.tag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbDocumentTagRepository extends JpaRepository<KbDocumentTag, Long> {
    List<KbDocumentTag> findByDocumentId(Long documentId);

    List<KbDocumentTag> findByTagId(Long tagId);

    void deleteByDocumentId(Long documentId);

    void deleteByTagId(Long tagId);

    boolean existsByDocumentIdAndTagId(Long documentId, Long tagId);
}
