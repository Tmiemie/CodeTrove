CREATE TABLE codetrove_platform_metadata (
    metadata_key VARCHAR(100) NOT NULL,
    metadata_value VARCHAR(500) NOT NULL,
    description VARCHAR(500) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (metadata_key)
);

INSERT INTO codetrove_platform_metadata (metadata_key, metadata_value, description)
VALUES ('schema_baseline', 'M0', 'CodeTrove initial database baseline');
