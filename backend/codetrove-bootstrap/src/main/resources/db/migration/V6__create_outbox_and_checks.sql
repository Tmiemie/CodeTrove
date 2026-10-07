CREATE TABLE codetrove_outbox_event (
    id BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    schema_version INT NOT NULL,
    topic_name VARCHAR(160) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_outbox_event_id UNIQUE (event_id),
    CONSTRAINT chk_codetrove_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT chk_codetrove_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_codetrove_outbox_ready
    ON codetrove_outbox_event (status, available_at, id);

CREATE TABLE codetrove_consumed_event (
    id BIGINT NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_consumed_event UNIQUE (consumer_name, event_id)
);

CREATE TABLE codetrove_check_suite (
    id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    head_commit CHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    is_current BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_check_suite_head UNIQUE (merge_request_id, head_commit),
    CONSTRAINT fk_codetrove_check_suite_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_check_suite_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED'))
);

CREATE INDEX idx_codetrove_check_suite_current
    ON codetrove_check_suite (merge_request_id, is_current, id);

CREATE TABLE codetrove_check_run (
    id BIGINT NOT NULL,
    check_suite_id BIGINT NOT NULL,
    check_type VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    blocking BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    conclusion VARCHAR(100) NULL,
    details_url VARCHAR(1000) NULL,
    attempt INT NOT NULL,
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_check_run_attempt UNIQUE (check_suite_id, name, attempt),
    CONSTRAINT fk_codetrove_check_run_suite
        FOREIGN KEY (check_suite_id) REFERENCES codetrove_check_suite (id) ON DELETE CASCADE,
    CONSTRAINT chk_codetrove_check_run_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT chk_codetrove_check_run_attempt CHECK (attempt > 0)
);

CREATE INDEX idx_codetrove_check_run_suite
    ON codetrove_check_run (check_suite_id, blocking, status);
