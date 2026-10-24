package com.northstar.audit.repository;

import com.northstar.audit.domain.SourceRecord;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface SourceRecordRepository extends JpaRepository<SourceRecord, Long> {
    @Query("select count(distinct r.sourceFile) from SourceRecord r")
    long countSourceFiles();

    List<SourceRecord> findTop100ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(String content);

    List<SourceRecord> findTop500ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(String content);

    List<SourceRecord> findTop500BySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
        String sourceFile, String content);

    List<SourceRecord> findAllBySourceFileOrderByRowNumberAsc(String sourceFile);

    Page<SourceRecord> findBySourceFileOrderByRowNumberAsc(String sourceFile, Pageable pageable);

    Page<SourceRecord> findBySourceFileAndContentContainingIgnoreCaseOrderByRowNumberAsc(
        String sourceFile, String content, Pageable pageable);

    @Query("select r.sourceFile, count(r) from SourceRecord r group by r.sourceFile")
    List<Object[]> countRowsBySourceFile();

    long countBySourceFile(String sourceFile);

    @Modifying
    @Transactional
    void deleteBySourceFile(String sourceFile);
}
