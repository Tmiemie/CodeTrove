CREATE TABLE codetrove_assay_execution (
    id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    head_commit CHAR(40) NOT NULL,
    check_run_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    conclusion VARCHAR(100) NULL,
    attempt INT NOT NULL,
    run_token CHAR(36) NULL,
    lease_until TIMESTAMP(6) NULL,
    total_count INT NOT NULL DEFAULT 0,
    passed_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_assay_execution_head UNIQUE (merge_request_id, head_commit),
    CONSTRAINT uk_codetrove_assay_execution_check_run UNIQUE (check_run_id),
    CONSTRAINT fk_codetrove_assay_execution_repository
        FOREIGN KEY (repository_id) REFERENCES codetrove_repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_assay_execution_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_assay_execution_check_run
        FOREIGN KEY (check_run_id) REFERENCES codetrove_check_run (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_assay_execution_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT chk_codetrove_assay_execution_attempt CHECK (attempt > 0),
    CONSTRAINT chk_codetrove_assay_execution_counts CHECK (
        total_count >= 0 AND passed_count >= 0 AND failed_count >= 0 AND skipped_count >= 0
        AND total_count = passed_count + failed_count + skipped_count
    )
);

CREATE INDEX idx_codetrove_assay_execution_mr_status
    ON codetrove_assay_execution (merge_request_id, status, id);

CREATE TABLE codetrove_assay_case_result (
    id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    case_key VARCHAR(160) NOT NULL,
    source_path VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64) NULL,
    duration_ms BIGINT NOT NULL,
    assertion_diff TEXT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_assay_case_result_key UNIQUE (execution_id, case_key),
    CONSTRAINT fk_codetrove_assay_case_result_execution
        FOREIGN KEY (execution_id) REFERENCES codetrove_assay_execution (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_assay_case_result_status
        CHECK (status IN ('PASSED', 'FAILED', 'ERROR', 'SKIPPED')),
    CONSTRAINT chk_codetrove_assay_case_result_duration CHECK (duration_ms >= 0)
);

CREATE INDEX idx_codetrove_assay_case_result_execution_status
    ON codetrove_assay_case_result (execution_id, status, id);
