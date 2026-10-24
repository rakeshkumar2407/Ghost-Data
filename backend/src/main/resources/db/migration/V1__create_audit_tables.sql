CREATE TABLE audit_case (
    case_id VARCHAR(120) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    requested_at TIMESTAMP,
    reported_status VARCHAR(40),
    reported_at TIMESTAMP,
    audit_at TIMESTAMP,
    request_text TEXT,
    deletion_status VARCHAR(24),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_step (
    id BIGSERIAL PRIMARY KEY,
    case_id VARCHAR(120) NOT NULL REFERENCES audit_case(case_id) ON DELETE CASCADE,
    step_no INTEGER NOT NULL,
    phase VARCHAR(20) NOT NULL,
    system_id VARCHAR(120) NOT NULL,
    resource_id VARCHAR(240) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    state VARCHAR(24) NOT NULL,
    evidence_id VARCHAR(160) NOT NULL,
    CONSTRAINT uq_audit_step_case_step UNIQUE (case_id, step_no)
);

CREATE INDEX idx_audit_case_status ON audit_case(deletion_status);
CREATE INDEX idx_audit_case_email ON audit_case(email);

CREATE TABLE source_record (
    id BIGSERIAL PRIMARY KEY,
    source_file VARCHAR(255) NOT NULL,
    row_number INTEGER NOT NULL,
    content TEXT NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_source_record_file_row UNIQUE (source_file, row_number)
);

CREATE INDEX idx_source_record_file ON source_record(source_file);
