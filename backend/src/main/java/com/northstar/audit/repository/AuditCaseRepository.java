package com.northstar.audit.repository;

import com.northstar.audit.domain.AuditCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditCaseRepository extends JpaRepository<AuditCase, String> {
    @Query("""
        select c from AuditCase c
        where (:status is null
               or (:status = 'PENDING' and c.deletionStatus is null)
               or (:status <> 'PENDING' and c.deletionStatus = :status))
          and (:search is null or lower(c.caseId) like lower(concat('%', :search, '%'))
               or lower(c.email) like lower(concat('%', :search, '%'))
               or lower(c.requestText) like lower(concat('%', :search, '%')))
        """)
    Page<AuditCase> findCases(@Param("search") String search, @Param("status") String status, Pageable pageable);

    long countByDeletionStatusIsNull();
    long countByDeletionStatus(String deletionStatus);
}
