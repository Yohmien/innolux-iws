CREATE TABLE IF NOT EXISTS iws_endpoint_catalog_control (
    singleton_key VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (singleton_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS iws_endpoint_system (
    system_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    enabled TINYINT NOT NULL,
    revision BIGINT NOT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (system_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS iws_endpoint_environment (
    environment_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    enabled TINYINT NOT NULL,
    revision BIGINT NOT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (environment_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS iws_endpoint_address (
    address_id BIGINT NOT NULL AUTO_INCREMENT,
    environment_code VARCHAR(64) NOT NULL,
    endpoint_ref VARCHAR(64) NOT NULL,
    protocol VARCHAR(8) NOT NULL,
    host VARCHAR(253) NOT NULL,
    port SMALLINT UNSIGNED NULL,
    base_path VARCHAR(512) NOT NULL,
    credential_ref VARCHAR(128) NULL,
    enabled TINYINT NOT NULL,
    revision BIGINT NOT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (address_id),
    UNIQUE KEY uk_iws_endpoint_address_env_ref (environment_code, endpoint_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS iws_endpoint_system_address (
    relation_id BIGINT NOT NULL AUTO_INCREMENT,
    system_code VARCHAR(64) NOT NULL,
    address_id BIGINT NOT NULL,
    enabled TINYINT NOT NULL,
    revision BIGINT NOT NULL,
    create_by BIGINT NULL,
    create_time DATETIME NOT NULL,
    update_by BIGINT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (relation_id),
    UNIQUE KEY uk_iws_endpoint_system_address (system_code, address_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS iws_endpoint_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    object_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(128) NOT NULL,
    environment_code VARCHAR(64) NULL,
    action VARCHAR(32) NOT NULL,
    before_summary TEXT NULL,
    after_summary TEXT NULL,
    operator_id BIGINT NULL,
    operator_name VARCHAR(128) NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    catalog_revision BIGINT NOT NULL,
    result_revision BIGINT NOT NULL,
    result_object_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    PRIMARY KEY (audit_id),
    UNIQUE KEY uk_iws_endpoint_audit_command (command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
