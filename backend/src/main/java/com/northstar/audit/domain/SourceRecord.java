package com.northstar.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "source_record")
public class SourceRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt = LocalDateTime.now();

    protected SourceRecord() {
    }

    public SourceRecord(String sourceFile, int rowNumber, String content) {
        this.sourceFile = sourceFile;
        this.rowNumber = rowNumber;
        this.content = content;
    }

    public String getSourceFile() { return sourceFile; }
    public int getRowNumber() { return rowNumber; }
    public String getContent() { return content; }
}
