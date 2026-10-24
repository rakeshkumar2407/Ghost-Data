package com.northstar.audit.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.northstar.audit.domain.AuditCase;
import com.northstar.audit.domain.SourceRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class AuditPersistenceTest {
    @Autowired
    private AuditCaseRepository cases;

    @Autowired
    private SourceRecordRepository sourceRecords;

    @Test
    void storesCasesAndSearchableSourceRows() {
        cases.save(new AuditCase("case_001", "person@example.test", null, "COMPLETE", null, null, "Delete my data"));
        sourceRecords.saveAll(List.of(
            new SourceRecord("user_identities.csv", 1, "{\"email\":\"person@example.test\"}"),
            new SourceRecord("system_activity.csv", 1, "{\"email\":\"other@example.test\"}")
        ));

        assertEquals(1, cases.count());
        assertEquals(1, sourceRecords.findTop100ByContentContainingIgnoreCaseOrderBySourceFileAscRowNumberAsc(
            "PERSON@example.test").size());
        assertEquals(2, sourceRecords.countSourceFiles());
    }
}
