ALTER TABLE codetrove_merge_request_comment
    DROP CONSTRAINT chk_codetrove_merge_request_comment_position;

ALTER TABLE codetrove_merge_request_comment
    ADD CONSTRAINT chk_codetrove_merge_request_comment_position
        CHECK (
            (type IN ('DIFF', 'AI_REVIEW')
                AND file_path IS NOT NULL
                AND side IS NOT NULL
                AND line_number IS NOT NULL
                AND line_number > 0
                AND commit_id IS NOT NULL)
            OR (type IN ('GENERAL', 'TEST_REPORT')
                AND file_path IS NULL
                AND side IS NULL
                AND line_number IS NULL
                AND commit_id IS NULL)
        );

CREATE TABLE codetrove_review_task (
    id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    head_commit CHAR(40) NOT NULL,
    check_run_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    conclusion VARCHAR(100) NULL,
    attempt INT NOT NULL,
    finding_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_review_task_head UNIQUE (merge_request_id, head_commit),
    CONSTRAINT uk_codetrove_review_task_check_run UNIQUE (check_run_id),
    CONSTRAINT fk_codetrove_review_task_repository
        FOREIGN KEY (repository_id) REFERENCES codetrove_repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_review_task_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_review_task_check_run
        FOREIGN KEY (check_run_id) REFERENCES codetrove_check_run (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_review_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT chk_codetrove_review_task_attempt CHECK (attempt > 0),
    CONSTRAINT chk_codetrove_review_task_finding_count CHECK (finding_count >= 0)
);

CREATE INDEX idx_codetrove_review_task_mr_status
    ON codetrove_review_task (merge_request_id, status, id);

CREATE TABLE codetrove_review_finding (
    id BIGINT NOT NULL,
    review_task_id BIGINT NOT NULL,
    skill VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    rule_id VARCHAR(100) NOT NULL,
    file_path VARCHAR(1024) NOT NULL,
    side VARCHAR(8) NOT NULL,
    line_number INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    evidence VARCHAR(500) NOT NULL,
    suggestion TEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_review_finding_fingerprint
        UNIQUE (review_task_id, fingerprint),
    CONSTRAINT fk_codetrove_review_finding_task
        FOREIGN KEY (review_task_id) REFERENCES codetrove_review_task (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_review_finding_skill
        CHECK (skill IN ('LOGIC', 'SECURITY')),
    CONSTRAINT chk_codetrove_review_finding_severity
        CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    CONSTRAINT chk_codetrove_review_finding_side CHECK (side IN ('OLD', 'NEW')),
    CONSTRAINT chk_codetrove_review_finding_line CHECK (line_number > 0),
    CONSTRAINT chk_codetrove_review_finding_disposition
        CHECK (disposition IN ('OPEN', 'ACCEPTED', 'FALSE_POSITIVE', 'FIXED'))
);

CREATE INDEX idx_codetrove_review_finding_task_severity
    ON codetrove_review_finding (review_task_id, severity, id);
