/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录事务数据访问；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.repository;

import com.innolux.iws.endpoint.domain.bo.EndpointAddressPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointAuditPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointEnvironmentPageBo;
import com.innolux.iws.endpoint.domain.bo.EndpointSystemPageBo;
import com.innolux.iws.endpoint.util.EndpointValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 端点目录事务数据访问。
 *
 * <p>所有写事务先独占锁定目录控制行；消费方事务先共享锁定同一行。
 * 该顺序是 endpoint 与 process 共用同库时的固定锁序。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "iws.endpoint", name = "enabled", havingValue = "true")
public class EndpointCatalogRepository {

    private static final String CONTROL_KEY = "CATALOG";
    private static final String CONTROL_TABLE = "iws_endpoint_catalog_control";

    private final JdbcTemplate jdbcTemplate;
    private final String sharedLockSql;

    /**
     * 创建端点目录数据访问。
     *
     * @param jdbcTemplate JDBC 模板
     * @param dataSource 数据源，用于识别 MySQL 共享锁语法
     */
    public EndpointCatalogRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.sharedLockSql = resolveSharedLockSql(dataSource);
    }

    /**
     * 独占锁定目录控制行；控制行不存在时创建初始版本。
     *
     * @return 当前目录版本
     */
    public long lockControl() {
        List<Long> revisions = jdbcTemplate.query(
            "SELECT revision FROM " + CONTROL_TABLE + " WHERE singleton_key = ? FOR UPDATE",
            (resultSet, rowNum) -> resultSet.getLong("revision"), CONTROL_KEY);
        if (revisions.isEmpty()) {
            try {
                jdbcTemplate.update("INSERT INTO " + CONTROL_TABLE
                    + " (singleton_key, revision, update_time) VALUES (?, 0, CURRENT_TIMESTAMP)", CONTROL_KEY);
            } catch (DuplicateKeyException ignored) {
                // 并发初始化时由已提交事务持有控制行。
            }
            revisions = jdbcTemplate.query(
                "SELECT revision FROM " + CONTROL_TABLE + " WHERE singleton_key = ? FOR UPDATE",
                (resultSet, rowNum) -> resultSet.getLong("revision"), CONTROL_KEY);
        }
        if (revisions.isEmpty()) {
            throw new IllegalStateException("端点目录控制行初始化失败");
        }
        return revisions.getFirst();
    }

    /**
     * 在调用方事务中共享锁定目录控制行。
     *
     * @return 当前目录版本
     */
    public long lockSharedControl() {
        List<Long> revisions = jdbcTemplate.query(
            String.format(Locale.ROOT, sharedLockSql, CONTROL_TABLE),
            (resultSet, rowNum) -> resultSet.getLong("revision"), CONTROL_KEY);
        if (revisions.isEmpty()) {
            throw new IllegalStateException("端点目录控制行不存在，请先执行 endpoint 表初始化脚本");
        }
        return revisions.getFirst();
    }

    /**
     * 读取当前目录版本，不建立锁。
     *
     * @return 当前目录版本
     */
    public long readControlRevision() {
        List<Long> revisions = jdbcTemplate.query(
            "SELECT revision FROM " + CONTROL_TABLE + " WHERE singleton_key = ?",
            (resultSet, rowNum) -> resultSet.getLong("revision"), CONTROL_KEY);
        if (revisions.isEmpty()) {
            throw new IllegalStateException("端点目录控制行不存在，请先执行 endpoint 表初始化脚本");
        }
        return revisions.getFirst();
    }

    /**
     * 在已持有独占锁时递增目录版本。
     *
     * @param currentRevision 当前目录版本
     * @return 新目录版本
     */
    public long advanceControl(long currentRevision) {
        int rows = jdbcTemplate.update("UPDATE " + CONTROL_TABLE
            + " SET revision = revision + 1, update_time = CURRENT_TIMESTAMP"
            + " WHERE singleton_key = ? AND revision = ?", CONTROL_KEY, currentRevision);
        if (rows != 1) {
            throw new IllegalStateException("目录控制行版本 CAS 失败");
        }
        return currentRevision + 1;
    }

    /**
     * 读取系统目录。
     *
     * @param systemCode 系统编码
     * @return 系统记录
     */
    public Optional<SystemRow> findSystem(String systemCode) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_system WHERE system_code = ?",
            systemMapper(), systemCode));
    }

    /**
     * 锁定并读取系统目录。
     *
     * @param systemCode 系统编码
     * @return 系统记录
     */
    public Optional<SystemRow> lockSystem(String systemCode) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_system WHERE system_code = ? FOR UPDATE",
            systemMapper(), systemCode));
    }

    /**
     * 分页读取系统目录。
     *
     * @param query 筛选条件
     * @param offset 偏移
     * @param limit 条数
     * @return 系统记录
     */
    public List<SystemRow> pageSystems(EndpointSystemPageBo query, long offset, long limit) {
        Filter filter = systemFilter(query);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_system" + filter.sql()
            + " ORDER BY update_time DESC, system_code ASC LIMIT ? OFFSET ?", systemMapper(), args.toArray());
    }

    /**
     * 统计系统目录。
     *
     * @param query 筛选条件
     * @return 总数
     */
    public long countSystems(EndpointSystemPageBo query) {
        Filter filter = systemFilter(query);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_endpoint_system" + filter.sql(),
            Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    /**
     * 新增系统目录。
     *
     * @param row 系统记录
     * @param operatorId 操作者 ID
     */
    public void insertSystem(SystemRow row, Long operatorId) {
        jdbcTemplate.update("INSERT INTO iws_endpoint_system"
                + " (system_code, display_name, description, enabled, revision, create_by, create_time, update_by, update_time)"
                + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
            row.systemCode(), row.displayName(), row.description(), row.enabled(), row.revision(), operatorId, operatorId);
    }

    /**
     * 更新系统目录。
     *
     * @param row 系统记录
     * @param expectedRevision 期望版本
     * @param operatorId 操作者 ID
     * @return 是否更新成功
     */
    public boolean updateSystem(SystemRow row, long expectedRevision, Long operatorId) {
        return jdbcTemplate.update("UPDATE iws_endpoint_system"
                + " SET display_name = ?, description = ?, enabled = ?, revision = ?, update_by = ?, update_time = CURRENT_TIMESTAMP"
                + " WHERE system_code = ? AND revision = ?",
            row.displayName(), row.description(), row.enabled(), row.revision(), operatorId,
            row.systemCode(), expectedRevision) == 1;
    }

    /**
     * 读取环境目录。
     *
     * @param environmentCode 环境编码
     * @return 环境记录
     */
    public Optional<EnvironmentRow> findEnvironment(String environmentCode) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_environment WHERE environment_code = ?",
            environmentMapper(), environmentCode));
    }

    /**
     * 锁定并读取环境目录。
     *
     * @param environmentCode 环境编码
     * @return 环境记录
     */
    public Optional<EnvironmentRow> lockEnvironment(String environmentCode) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_environment WHERE environment_code = ? FOR UPDATE",
            environmentMapper(), environmentCode));
    }

    /**
     * 分页读取环境目录。
     *
     * @param query 筛选条件
     * @param offset 偏移
     * @param limit 条数
     * @return 环境记录
     */
    public List<EnvironmentRow> pageEnvironments(EndpointEnvironmentPageBo query, long offset, long limit) {
        Filter filter = environmentFilter(query);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_environment" + filter.sql()
            + " ORDER BY update_time DESC, environment_code ASC LIMIT ? OFFSET ?",
            environmentMapper(), args.toArray());
    }

    /**
     * 统计环境目录。
     *
     * @param query 筛选条件
     * @return 总数
     */
    public long countEnvironments(EndpointEnvironmentPageBo query) {
        Filter filter = environmentFilter(query);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_endpoint_environment" + filter.sql(),
            Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    /**
     * 新增环境目录。
     *
     * @param row 环境记录
     * @param operatorId 操作者 ID
     */
    public void insertEnvironment(EnvironmentRow row, Long operatorId) {
        jdbcTemplate.update("INSERT INTO iws_endpoint_environment"
                + " (environment_code, display_name, description, enabled, revision, create_by, create_time, update_by, update_time)"
                + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
            row.environmentCode(), row.displayName(), row.description(), row.enabled(), row.revision(), operatorId, operatorId);
    }

    /**
     * 更新环境目录。
     *
     * @param row 环境记录
     * @param expectedRevision 期望版本
     * @param operatorId 操作者 ID
     * @return 是否更新成功
     */
    public boolean updateEnvironment(EnvironmentRow row, long expectedRevision, Long operatorId) {
        return jdbcTemplate.update("UPDATE iws_endpoint_environment"
                + " SET display_name = ?, description = ?, enabled = ?, revision = ?, update_by = ?, update_time = CURRENT_TIMESTAMP"
                + " WHERE environment_code = ? AND revision = ?",
            row.displayName(), row.description(), row.enabled(), row.revision(), operatorId,
            row.environmentCode(), expectedRevision) == 1;
    }

    /**
     * 读取地址。
     *
     * @param addressId 地址主键
     * @return 地址记录
     */
    public Optional<AddressRow> findAddress(long addressId) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_address WHERE address_id = ?",
            addressMapper(), addressId));
    }

    /**
     * 锁定并读取地址。
     *
     * @param addressId 地址主键
     * @return 地址记录
     */
    public Optional<AddressRow> lockAddress(long addressId) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_address WHERE address_id = ? FOR UPDATE",
            addressMapper(), addressId));
    }

    /**
     * 按环境和引用读取地址。
     *
     * @param environmentCode 环境编码
     * @param endpointRef 地址引用
     * @return 地址记录
     */
    public Optional<AddressRow> findAddress(String environmentCode, String endpointRef) {
        return first(jdbcTemplate.query(
            "SELECT * FROM iws_endpoint_address WHERE environment_code = ? AND endpoint_ref = ?",
            addressMapper(), environmentCode, endpointRef));
    }

    /**
     * 分页读取地址。
     *
     * @param query 筛选条件
     * @param offset 偏移
     * @param limit 条数
     * @return 地址记录
     */
    public List<AddressRow> pageAddresses(EndpointAddressPageBo query, long offset, long limit) {
        Filter filter = addressFilter(query);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("SELECT a.* FROM iws_endpoint_address a" + filter.sql()
            + " ORDER BY a.update_time DESC, a.address_id DESC LIMIT ? OFFSET ?", addressMapper(), args.toArray());
    }

    /**
     * 统计地址。
     *
     * @param query 筛选条件
     * @return 总数
     */
    public long countAddresses(EndpointAddressPageBo query) {
        Filter filter = addressFilter(query);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_endpoint_address a" + filter.sql(),
            Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    /**
     * 新增地址。
     *
     * @param row 地址记录
     * @param operatorId 操作者 ID
     * @return 新地址主键
     */
    public long insertAddress(AddressRow row, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("INSERT INTO iws_endpoint_address"
                    + " (environment_code, endpoint_ref, protocol, host, port, base_path, credential_ref, enabled, revision,"
                    + " create_by, create_time, update_by, update_time)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, row.environmentCode());
            statement.setString(2, row.endpointRef());
            statement.setString(3, row.protocol());
            statement.setString(4, row.host());
            if (row.port() == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setInt(5, row.port());
            }
            statement.setString(6, row.basePath());
            statement.setString(7, row.credentialRef());
            statement.setBoolean(8, row.enabled());
            statement.setLong(9, row.revision());
            statement.setObject(10, operatorId);
            statement.setObject(11, operatorId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("新增端点地址未返回主键");
        }
        return key.longValue();
    }

    /**
     * 更新地址。
     *
     * @param row 地址记录
     * @param expectedRevision 期望版本
     * @param operatorId 操作者 ID
     * @return 是否更新成功
     */
    public boolean updateAddress(AddressRow row, long expectedRevision, Long operatorId) {
        return jdbcTemplate.update("UPDATE iws_endpoint_address"
                + " SET protocol = ?, host = ?, port = ?, base_path = ?, credential_ref = ?, enabled = ?, revision = ?,"
                + " update_by = ?, update_time = CURRENT_TIMESTAMP WHERE address_id = ? AND revision = ?",
            row.protocol(), row.host(), row.port(), row.basePath(), row.credentialRef(), row.enabled(), row.revision(),
            operatorId, row.addressId(), expectedRevision) == 1;
    }

    /**
     * 读取某系统全部关联，包含停用历史行。
     *
     * @param systemCode 系统编码
     * @return 关联记录
     */
    public List<RelationRow> relationsForSystem(String systemCode) {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_system_address WHERE system_code = ?",
            relationMapper(), systemCode);
    }

    /**
     * 读取某地址全部关联，包含停用历史行。
     *
     * @param addressId 地址主键
     * @return 关联记录
     */
    public List<RelationRow> relationsForAddress(long addressId) {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_system_address WHERE address_id = ?",
            relationMapper(), addressId);
    }

    /**
     * 读取某系统当前有效关联。
     *
     * @param systemCode 系统编码
     * @return 生效关联
     */
    public List<RelationRow> activeRelationsForSystem(String systemCode) {
        return jdbcTemplate.query(
            "SELECT * FROM iws_endpoint_system_address WHERE system_code = ? AND enabled = 1 ORDER BY address_id",
            relationMapper(), systemCode);
    }

    /**
     * 读取某地址当前启用的系统编码。
     *
     * @param addressId 地址主键
     * @return 系统编码
     */
    public List<String> activeSystemCodesForAddress(long addressId) {
        return jdbcTemplate.query(
            "SELECT system_code FROM iws_endpoint_system_address WHERE address_id = ? AND enabled = 1 ORDER BY system_code",
            (resultSet, rowNum) -> resultSet.getString("system_code"), addressId);
    }

    /**
     * 新增系统地址关联。
     *
     * @param systemCode 系统编码
     * @param addressId 地址主键
     * @param operatorId 操作者 ID
     */
    public void insertRelation(String systemCode, long addressId, Long operatorId) {
        jdbcTemplate.update("INSERT INTO iws_endpoint_system_address"
                + " (system_code, address_id, enabled, revision, create_by, create_time, update_by, update_time)"
                + " VALUES (?, ?, 1, 1, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)",
            systemCode, addressId, operatorId, operatorId);
    }

    /**
     * 更新系统地址关联启停与版本。
     *
     * @param relationId 关联主键
     * @param enabled 是否启用
     * @param revision 新版本
     * @param expectedRevision 期望版本
     * @param operatorId 操作者 ID
     * @return 是否更新成功
     */
    public boolean updateRelation(long relationId, boolean enabled, long revision,
                                  long expectedRevision, Long operatorId) {
        return jdbcTemplate.update("UPDATE iws_endpoint_system_address"
                + " SET enabled = ?, revision = ?, update_by = ?, update_time = CURRENT_TIMESTAMP"
                + " WHERE relation_id = ? AND revision = ?",
            enabled, revision, operatorId, relationId, expectedRevision) == 1;
    }

    /**
     * 读取全部系统目录快照。
     *
     * @return 系统记录
     */
    public List<SystemRow> allSystems() {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_system ORDER BY system_code", systemMapper());
    }

    /**
     * 读取全部环境目录快照。
     *
     * @return 环境记录
     */
    public List<EnvironmentRow> allEnvironments() {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_environment ORDER BY environment_code", environmentMapper());
    }

    /**
     * 读取全部地址快照。
     *
     * @return 地址记录
     */
    public List<AddressRow> allAddresses() {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_address ORDER BY address_id", addressMapper());
    }

    /**
     * 读取全部系统地址关联快照。
     *
     * @return 关联记录
     */
    public List<RelationRow> allRelations() {
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_system_address ORDER BY system_code, address_id",
            relationMapper());
    }

    /**
     * 按命令键读取已提交命令审计。
     *
     * @param commandKey 命令键
     * @return 审计记录
     */
    public Optional<AuditRow> findCommand(String commandKey) {
        return first(jdbcTemplate.query("SELECT * FROM iws_endpoint_audit WHERE command_key = ?",
            auditMapper(), commandKey));
    }

    /**
     * 追加端点配置审计。
     *
     * @param entry 审计记录
     */
    public void insertAudit(AuditEntry entry) {
        jdbcTemplate.update("INSERT INTO iws_endpoint_audit"
                + " (object_type, object_key, environment_code, action, before_summary, after_summary,"
                + " operator_id, operator_name, command_key, request_hash, reason, catalog_revision,"
                + " result_revision, result_object_key, create_time)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            entry.objectType(), entry.objectKey(), entry.environmentCode(), entry.action(),
            entry.beforeSummary(), entry.afterSummary(), entry.operatorId(), entry.operatorName(),
            entry.commandKey(), entry.requestHash(), entry.reason(), entry.catalogRevision(),
            entry.resultRevision(), entry.resultObjectKey());
    }

    /**
     * 分页读取配置审计。
     *
     * @param query 筛选条件
     * @param offset 偏移
     * @param limit 条数
     * @return 审计记录
     */
    public List<AuditRow> pageAudits(EndpointAuditPageBo query, long offset, long limit) {
        Filter filter = auditFilter(query);
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query("SELECT * FROM iws_endpoint_audit" + filter.sql()
            + " ORDER BY audit_id DESC LIMIT ? OFFSET ?", auditMapper(), args.toArray());
    }

    /**
     * 统计配置审计。
     *
     * @param query 筛选条件
     * @return 总数
     */
    public long countAudits(EndpointAuditPageBo query) {
        Filter filter = auditFilter(query);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iws_endpoint_audit" + filter.sql(),
            Long.class, filter.args().toArray());
        return total == null ? 0L : total;
    }

    private String resolveSharedLockSql(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT);
            if (product.contains("MYSQL")) {
                return "SELECT revision FROM %s WHERE singleton_key = ? FOR SHARE";
            }
            // H2 等隔离测试库不支持 FOR SHARE；FOR UPDATE 更强，不改变共享门禁的成立条件。
            return "SELECT revision FROM %s WHERE singleton_key = ? FOR UPDATE";
        } catch (SQLException exception) {
            throw new IllegalStateException("无法识别端点目录数据库类型", exception);
        }
    }

    private Filter systemFilter(EndpointSystemPageBo query) {
        Conditions conditions = new Conditions();
        conditions.like("system_code", query.getSystemCode());
        conditions.like("display_name", query.getDisplayName());
        conditions.equal("enabled", query.getEnabled());
        return conditions.build();
    }

    private Filter environmentFilter(EndpointEnvironmentPageBo query) {
        Conditions conditions = new Conditions();
        conditions.like("environment_code", query.getEnvironmentCode());
        conditions.like("display_name", query.getDisplayName());
        conditions.equal("enabled", query.getEnabled());
        return conditions.build();
    }

    private Filter addressFilter(EndpointAddressPageBo query) {
        Conditions conditions = new Conditions();
        conditions.equal("a.environment_code", query.getEnvironmentCode());
        conditions.like("a.endpoint_ref", query.getEndpointRef());
        conditions.like("a.host", query.getHost());
        conditions.equal("a.enabled", query.getEnabled());
        if (EndpointValidator.optional(query.getSystemCode()) != null) {
            conditions.raw("EXISTS (SELECT 1 FROM iws_endpoint_system_address sa"
                + " WHERE sa.address_id = a.address_id AND sa.system_code = ? AND sa.enabled = 1)",
                query.getSystemCode().trim());
        }
        return conditions.build();
    }

    private Filter auditFilter(EndpointAuditPageBo query) {
        Conditions conditions = new Conditions();
        conditions.equal("object_type", query.getObjectType());
        conditions.like("object_key", query.getObjectKey());
        conditions.equal("environment_code", query.getEnvironmentCode());
        conditions.like("operator_name", query.getOperatorName());
        conditions.equal("command_key", query.getCommandKey());
        return conditions.build();
    }

    private RowMapper<SystemRow> systemMapper() {
        return (resultSet, rowNum) -> new SystemRow(
            resultSet.getString("system_code"),
            resultSet.getString("display_name"),
            resultSet.getString("description"),
            resultSet.getBoolean("enabled"),
            resultSet.getLong("revision"),
            time(resultSet, "update_time"));
    }

    private RowMapper<EnvironmentRow> environmentMapper() {
        return (resultSet, rowNum) -> new EnvironmentRow(
            resultSet.getString("environment_code"),
            resultSet.getString("display_name"),
            resultSet.getString("description"),
            resultSet.getBoolean("enabled"),
            resultSet.getLong("revision"),
            time(resultSet, "update_time"));
    }

    private RowMapper<AddressRow> addressMapper() {
        return (resultSet, rowNum) -> new AddressRow(
            resultSet.getLong("address_id"),
            resultSet.getString("environment_code"),
            resultSet.getString("endpoint_ref"),
            resultSet.getString("protocol"),
            resultSet.getString("host"),
            nullableInt(resultSet, "port"),
            resultSet.getString("base_path"),
            resultSet.getString("credential_ref"),
            resultSet.getBoolean("enabled"),
            resultSet.getLong("revision"),
            time(resultSet, "update_time"));
    }

    private RowMapper<RelationRow> relationMapper() {
        return (resultSet, rowNum) -> new RelationRow(
            resultSet.getLong("relation_id"),
            resultSet.getString("system_code"),
            resultSet.getLong("address_id"),
            resultSet.getBoolean("enabled"),
            resultSet.getLong("revision"));
    }

    private RowMapper<AuditRow> auditMapper() {
        return (resultSet, rowNum) -> new AuditRow(
            resultSet.getLong("audit_id"),
            resultSet.getString("object_type"),
            resultSet.getString("object_key"),
            resultSet.getString("environment_code"),
            resultSet.getString("action"),
            resultSet.getString("before_summary"),
            resultSet.getString("after_summary"),
            nullableLong(resultSet, "operator_id"),
            resultSet.getString("operator_name"),
            resultSet.getString("command_key"),
            resultSet.getString("request_hash"),
            resultSet.getString("reason"),
            resultSet.getLong("catalog_revision"),
            resultSet.getLong("result_revision"),
            resultSet.getString("result_object_key"),
            time(resultSet, "create_time"));
    }

    private Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime time(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private <T> Optional<T> first(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private record Filter(String sql, List<Object> args) {
    }

    private static final class Conditions {

        private final List<String> clauses = new ArrayList<>();
        private final List<Object> args = new ArrayList<>();

        private void like(String column, String value) {
            String normalized = EndpointValidator.optional(value);
            if (normalized != null) {
                clauses.add(column + " LIKE ?");
                args.add("%" + normalized + "%");
            }
        }

        private void equal(String column, Object value) {
            if (value != null) {
                clauses.add(column + " = ?");
                args.add(value);
            }
        }

        private void raw(String clause, Object... values) {
            clauses.add(clause);
            args.addAll(List.of(values));
        }

        private Filter build() {
            return new Filter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), List.copyOf(args));
        }
    }

    /**
     * 系统目录行。
     */
    public record SystemRow(String systemCode, String displayName, String description, boolean enabled,
                            long revision, LocalDateTime updateTime) {
    }

    /**
     * 环境目录行。
     */
    public record EnvironmentRow(String environmentCode, String displayName, String description, boolean enabled,
                                 long revision, LocalDateTime updateTime) {
    }

    /**
     * 地址目录行。
     */
    public record AddressRow(long addressId, String environmentCode, String endpointRef, String protocol,
                             String host, Integer port, String basePath, String credentialRef,
                             boolean enabled, long revision, LocalDateTime updateTime) {
    }

    /**
     * 系统地址关联行。
     */
    public record RelationRow(long relationId, String systemCode, long addressId, boolean enabled, long revision) {
    }

    /**
     * 审计行。
     */
    public record AuditRow(long auditId, String objectType, String objectKey, String environmentCode,
                           String action, String beforeSummary, String afterSummary, Long operatorId,
                           String operatorName, String commandKey, String requestHash, String reason,
                           long catalogRevision, long resultRevision, String resultObjectKey,
                           LocalDateTime createTime) {
    }

    /**
     * 新审计记录。
     */
    public record AuditEntry(String objectType, String objectKey, String environmentCode, String action,
                             String beforeSummary, String afterSummary, Long operatorId, String operatorName,
                             String commandKey, String requestHash, String reason, long catalogRevision,
                             long resultRevision, String resultObjectKey) {
    }
}
