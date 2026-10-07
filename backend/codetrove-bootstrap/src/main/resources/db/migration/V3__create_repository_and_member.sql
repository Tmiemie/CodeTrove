CREATE TABLE codetrove_repository (
    id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    visibility VARCHAR(16) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_codetrove_repository_owner_slug UNIQUE (owner_id, slug),
    CONSTRAINT fk_codetrove_repository_owner FOREIGN KEY (owner_id) REFERENCES codetrove_user (id),
    CONSTRAINT chk_codetrove_repository_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC')),
    CONSTRAINT chk_codetrove_repository_status CHECK (status IN ('INITIALIZING', 'ACTIVE', 'ARCHIVED', 'ERROR'))
);

CREATE TABLE codetrove_repository_member (
    repository_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (repository_id, user_id),
    CONSTRAINT fk_codetrove_repository_member_repository
        FOREIGN KEY (repository_id) REFERENCES codetrove_repository (id) ON DELETE CASCADE,
    CONSTRAINT fk_codetrove_repository_member_user
        FOREIGN KEY (user_id) REFERENCES codetrove_user (id),
    CONSTRAINT chk_codetrove_repository_member_role
        CHECK (role IN ('OWNER', 'MAINTAINER', 'DEVELOPER', 'REPORTER'))
);

CREATE INDEX idx_codetrove_repository_member_user
    ON codetrove_repository_member (user_id, repository_id);
CREATE INDEX idx_codetrove_repository_visibility_status
    ON codetrove_repository (visibility, status, id);
