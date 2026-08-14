-- Brick Platform Database Schema
-- Version: 1.0

CREATE DATABASE IF NOT EXISTS brick_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE brick_platform;

-- Swagger映射表
CREATE TABLE IF NOT EXISTS brick_app_swagger_mapping (
    id INT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(100),
    app_config_id VARCHAR(64),
    real_name VARCHAR(100),
    swagger_url VARCHAR(500),
    env VARCHAR(20),
    active TINYINT DEFAULT 1,
    owner VARCHAR(50),
    ext_json TEXT,
    version_tag VARCHAR(100),
    branch_name VARCHAR(200),
    project_url VARCHAR(500),
    is_deleted TINYINT NOT NULL DEFAULT 0,
    coverage_rate DECIMAL(5,2),
    coverage_rate_numerator DECIMAL(10,2),
    coverage_rate_denominator DECIMAL(10,2),
    last_30_days_coverage_rate DECIMAL(5,2),
    coverage_rate_type INT,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_env_app_version (env, app_config_id, version_tag),
    INDEX idx_env_app (env, app_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 接口定义表
CREATE TABLE IF NOT EXISTS brick_endpoint_definition (
    id INT PRIMARY KEY AUTO_INCREMENT,
    env VARCHAR(20),
    swagger_mapping_id INT,
    app_config_id VARCHAR(64),
    protocol VARCHAR(10),
    host VARCHAR(200),
    base_path VARCHAR(200),
    endpoint_path VARCHAR(500),
    full_url VARCHAR(1000),
    http_method VARCHAR(10),
    operation_id VARCHAR(200),
    summary VARCHAR(500),
    description TEXT,
    tags VARCHAR(500),
    deprecated TINYINT DEFAULT 0,
    swagger_version VARCHAR(10),
    consumes_types VARCHAR(200),
    produces_types VARCHAR(200),
    swagger_url VARCHAR(500),
    doc_checksum VARCHAR(100),
    is_lightweight TINYINT DEFAULT 0,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_swagger_method_path (swagger_mapping_id, http_method, endpoint_path),
    INDEX idx_swagger_mapping (swagger_mapping_id),
    INDEX idx_env_app (env, app_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程主表
CREATE TABLE IF NOT EXISTS brick_flow (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200),
    env VARCHAR(20),
    swagger_mapping_id INT,
    app_config_id VARCHAR(64),
    token_config_id BIGINT,
    description TEXT,
    status VARCHAR(20) DEFAULT 'draft',
    version INT DEFAULT 0,
    flow_template_id INT DEFAULT -1,
    is_deleted TINYINT DEFAULT 0,
    source TINYINT DEFAULT 0,
    shared_headers_json TEXT,
    viewport_x DOUBLE,
    viewport_y DOUBLE,
    viewport_zoom DOUBLE,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_swagger_mapping (swagger_mapping_id),
    INDEX idx_env_app (env, app_config_id),
    INDEX idx_update_time (update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程节点表
CREATE TABLE IF NOT EXISTS brick_flow_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id INT,
    endpoint_id INT,
    timeout_sec INT DEFAULT 30,
    retries INT DEFAULT 0,
    headers_json TEXT,
    payload_json TEXT,
    query_params_json TEXT,
    path_vars_json TEXT,
    condition_group_id INT,
    token_config_id BIGINT,
    sign_config_id BIGINT,
    sign_enabled TINYINT DEFAULT 0,
    node_type VARCHAR(20) DEFAULT 'http',
    grpc_endpoint_id INT,
    grpc_discovery_config TEXT,
    x DOUBLE,
    y DOUBLE,
    is_deleted TINYINT DEFAULT 0,
    endpoint_app_config_id VARCHAR(64),
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_flow_id (flow_id),
    INDEX idx_endpoint_id (endpoint_id),
    INDEX idx_grpc_endpoint_id (grpc_endpoint_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程连线表
CREATE TABLE IF NOT EXISTS brick_flow_edge (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id INT,
    source_node_id BIGINT,
    target_node_id BIGINT,
    edge_type VARCHAR(20) DEFAULT 'default',
    condition_json TEXT,
    is_deleted TINYINT DEFAULT 0,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_flow_id (flow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程执行记录表
CREATE TABLE IF NOT EXISTS brick_flow_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id INT,
    status VARCHAR(20),
    triggered_by VARCHAR(50),
    run_type INT DEFAULT 0,
    duration_ms BIGINT,
    error_msg TEXT,
    override_base_url VARCHAR(500),
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    suite_run_id BIGINT,
    batch_id VARCHAR(100),
    remark TEXT,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_flow_id (flow_id),
    INDEX idx_flow_start_time (flow_id, start_time),
    INDEX idx_suite_run (suite_run_id),
    INDEX idx_batch_id (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 节点执行记录表
CREATE TABLE IF NOT EXISTS brick_flow_run_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT,
    node_id BIGINT,
    endpoint_id INT,
    grpc_endpoint_id INT,
    status VARCHAR(20),
    http_status INT,
    duration_ms BIGINT,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    request_method VARCHAR(10),
    request_url VARCHAR(1000),
    request_headers TEXT,
    request_body TEXT,
    request_query_params TEXT,
    request_path_params TEXT,
    response_headers TEXT,
    response_preview TEXT,
    full_response LONGTEXT,
    response_size INT,
    error_msg TEXT,
    assertion_total_count INT,
    assertion_passed_count INT,
    assertion_failed_count INT,
    assertion_summary TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_run_id (run_id),
    INDEX idx_node_id (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 测试集表
CREATE TABLE IF NOT EXISTS brick_test_suite (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200),
    env VARCHAR(20),
    swagger_mapping_id INT,
    app_config_id VARCHAR(64),
    description TEXT,
    is_deleted TINYINT DEFAULT 0,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_swagger_mapping (swagger_mapping_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 测试集流程映射表
CREATE TABLE IF NOT EXISTS brick_test_suite_flow_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suite_id INT,
    flow_id INT,
    execution_order INT,
    is_deleted TINYINT DEFAULT 0,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_suite_flow (suite_id, flow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 测试集执行记录表
CREATE TABLE IF NOT EXISTS brick_test_suite_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    suite_id INT,
    status VARCHAR(20),
    operator VARCHAR(50),
    total_flows INT,
    success_flows INT,
    failed_flows INT,
    duration_ms BIGINT,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    error_msg TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_suite_id (suite_id),
    INDEX idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 全域变量表
CREATE TABLE IF NOT EXISTS brick_global_variable (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200),
    type VARCHAR(50),
    description TEXT,
    config TEXT,
    is_enabled TINYINT DEFAULT 1,
    category VARCHAR(100),
    syntax VARCHAR(500),
    has_params TINYINT DEFAULT 0,
    example TEXT,
    sample_result TEXT,
    param_schema TEXT,
    data_type VARCHAR(20),
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_by VARCHAR(50),
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- HAR导入报告表
CREATE TABLE IF NOT EXISTS brick_har_import_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    swagger_mapping_id INT,
    total_count INT,
    success_count INT,
    failed_count INT,
    success_detail TEXT,
    failed_detail TEXT,
    operator VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Swagger同步日志表
CREATE TABLE IF NOT EXISTS brick_swagger_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    swagger_mapping_id INT,
    sync_type VARCHAR(20),
    sync_status VARCHAR(20),
    start_time DATETIME,
    end_time DATETIME,
    duration_ms BIGINT,
    interfaces_before INT,
    interfaces_after INT,
    interfaces_added INT,
    interfaces_updated INT,
    interfaces_deleted INT,
    git_merge_branches TEXT,
    create_by VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程节点断言配置表
CREATE TABLE IF NOT EXISTS brick_flow_node_assertion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    node_id BIGINT,
    assertion_type VARCHAR(50),
    field_path VARCHAR(500),
    operator VARCHAR(20),
    expected_value VARCHAR(500),
    is_enabled TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 流程节点断言执行记录表
CREATE TABLE IF NOT EXISTS brick_flow_run_node_assertion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_node_id BIGINT,
    assertion_id BIGINT,
    status VARCHAR(20),
    actual_value TEXT,
    expected_value VARCHAR(500),
    error_msg TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 需求测试任务表
CREATE TABLE IF NOT EXISTS brick_requirement_test_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    requirement_id VARCHAR(100),
    requirement_title VARCHAR(500),
    swagger_mapping_id INT,
    total_flows INT,
    completed_flows INT,
    status VARCHAR(20),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 报告映射表
CREATE TABLE IF NOT EXISTS brick_report_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT,
    suite_id INT,
    flow_id INT,
    operator VARCHAR(50),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
