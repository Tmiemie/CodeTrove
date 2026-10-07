ALTER TABLE codetrove_merge_request
    ADD COLUMN merge_commit CHAR(40) NULL;

CREATE TABLE codetrove_merge_request_commit (
    id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    commit_id CHAR(40) NOT NULL,
    sequence_number INT NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_mr_commit_commit UNIQUE (merge_request_id, commit_id),
    CONSTRAINT uk_codetrove_mr_commit_sequence UNIQUE (merge_request_id, sequence_number),
    CONSTRAINT fk_codetrove_mr_commit_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_mr_commit_sequence CHECK (sequence_number > 0)
);

CREATE TABLE codetrove_merge_operation (
    id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expected_head_commit CHAR(40) NOT NULL,
    target_before_commit CHAR(40) NOT NULL,
    merge_commit CHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    merged_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_merge_operation_key UNIQUE (repository_id, idempotency_key),
    CONSTRAINT fk_codetrove_merge_operation_repository
        FOREIGN KEY (repository_id) REFERENCES codetrove_repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_merge_operation_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_merge_operation_user
        FOREIGN KEY (merged_by) REFERENCES codetrove_user (id),
    CONSTRAINT chk_codetrove_merge_operation_status CHECK (status IN ('PENDING', 'SUCCEEDED'))
);

CREATE INDEX idx_codetrove_mr_commit_mr_sequence
    ON codetrove_merge_request_commit (merge_request_id, sequence_number);
CREATE INDEX idx_codetrove_merge_operation_mr_status
    ON codetrove_merge_operation (merge_request_id, status);
