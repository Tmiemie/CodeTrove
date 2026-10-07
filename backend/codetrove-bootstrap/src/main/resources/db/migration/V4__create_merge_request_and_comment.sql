CREATE TABLE codetrove_merge_request (
    id BIGINT NOT NULL,
    repository_id BIGINT NOT NULL,
    iid INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    source_branch VARCHAR(255) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    base_commit CHAR(40) NOT NULL,
    head_commit CHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    author_id BIGINT NOT NULL,
    merged_by BIGINT NULL,
    merged_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_merge_request_repository_iid
        UNIQUE (repository_id, iid),
    CONSTRAINT fk_codetrove_merge_request_repository
        FOREIGN KEY (repository_id) REFERENCES codetrove_repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_merge_request_author
        FOREIGN KEY (author_id) REFERENCES codetrove_user (id),
    CONSTRAINT fk_codetrove_merge_request_merged_by
        FOREIGN KEY (merged_by) REFERENCES codetrove_user (id),
    CONSTRAINT chk_codetrove_merge_request_status
        CHECK (status IN ('OPEN', 'MERGED', 'CLOSED')),
    CONSTRAINT chk_codetrove_merge_request_branches
        CHECK (source_branch <> target_branch),
    CONSTRAINT chk_codetrove_merge_request_merge_fields
        CHECK (
            (status = 'MERGED' AND merged_by IS NOT NULL AND merged_at IS NOT NULL)
            OR (status <> 'MERGED' AND merged_by IS NULL AND merged_at IS NULL)
        )
);

CREATE INDEX idx_codetrove_merge_request_repository_status_id
    ON codetrove_merge_request (repository_id, status, id);
CREATE INDEX idx_codetrove_merge_request_repository_target_id
    ON codetrove_merge_request (repository_id, target_branch, id);
CREATE INDEX idx_codetrove_merge_request_author_id
    ON codetrove_merge_request (author_id, id);

CREATE TABLE codetrove_merge_request_comment (
    id BIGINT NOT NULL,
    merge_request_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    file_path VARCHAR(1024) NULL,
    side VARCHAR(8) NULL,
    line_number INT NULL,
    commit_id CHAR(40) NULL,
    author_id BIGINT NULL,
    author_type VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    fingerprint VARCHAR(128) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_codetrove_merge_request_comment_mr
        FOREIGN KEY (merge_request_id) REFERENCES codetrove_merge_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_merge_request_comment_author
        FOREIGN KEY (author_id) REFERENCES codetrove_user (id),
    CONSTRAINT chk_codetrove_merge_request_comment_type
        CHECK (type IN ('GENERAL', 'DIFF', 'AI_REVIEW', 'TEST_REPORT')),
    CONSTRAINT chk_codetrove_merge_request_comment_side
        CHECK (side IS NULL OR side IN ('OLD', 'NEW')),
    CONSTRAINT chk_codetrove_merge_request_comment_author_type
        CHECK (author_type IN ('USER', 'SYSTEM')),
    CONSTRAINT chk_codetrove_merge_request_comment_position
        CHECK (
            (type = 'DIFF'
                AND file_path IS NOT NULL
                AND side IS NOT NULL
                AND line_number IS NOT NULL
                AND line_number > 0
                AND commit_id IS NOT NULL)
            OR (type <> 'DIFF'
                AND file_path IS NULL
                AND side IS NULL
                AND line_number IS NULL
                AND commit_id IS NULL)
        ),
    CONSTRAINT chk_codetrove_merge_request_comment_author
        CHECK (
            (author_type = 'USER' AND author_id IS NOT NULL)
            OR author_type = 'SYSTEM'
        )
);

CREATE INDEX idx_codetrove_merge_request_comment_mr_id
    ON codetrove_merge_request_comment (merge_request_id, id);
CREATE UNIQUE INDEX uk_codetrove_merge_request_comment_fingerprint
    ON codetrove_merge_request_comment (merge_request_id, fingerprint);
