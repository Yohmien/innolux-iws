/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 基于真实 endpoint 表的隔离目录 Reader；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.definition;

import com.innolux.iws.endpoint.catalog.EndpointAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointCatalogConflictException;
import com.innolux.iws.endpoint.catalog.EndpointCatalogReader;
import com.innolux.iws.endpoint.catalog.EndpointCatalogSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointEnvironmentSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemAddressSnapshot;
import com.innolux.iws.endpoint.catalog.EndpointSystemSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 读取真实 endpoint 目录表的隔离 Reader。
 *
 * <p>共享锁语义与正式 Reader 一致：先锁 catalog_control 行并比对 revision，再返回不可变快照。</p>
 */
final class EndpointCatalogFixtureReader implements EndpointCatalogReader {

    private final JdbcTemplate jdbcTemplate;

    private final String currentEnvironment;

    EndpointCatalogFixtureReader(JdbcTemplate jdbcTemplate, String currentEnvironment) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentEnvironment = currentEnvironment;
    }

    @Override
    public EndpointCatalogSnapshot snapshot() {
        Long revision = jdbcTemplate.queryForObject(
            "SELECT revision FROM iws_endpoint_catalog_control WHERE singleton_key = 'CATALOG'", Long.class);
        List<EndpointSystemSnapshot> systems = jdbcTemplate.query(
            "SELECT system_code, display_name, enabled, revision FROM iws_endpoint_system ORDER BY system_code",
            (resultSet, index) -> new EndpointSystemSnapshot(resultSet.getString("system_code"),
                resultSet.getString("display_name"), resultSet.getBoolean("enabled"),
                resultSet.getString("revision")));
        List<EndpointEnvironmentSnapshot> environments = jdbcTemplate.query(
            "SELECT environment_code, display_name, enabled, revision FROM iws_endpoint_environment"
                + " ORDER BY environment_code",
            (resultSet, index) -> new EndpointEnvironmentSnapshot(resultSet.getString("environment_code"),
                resultSet.getString("display_name"), resultSet.getBoolean("enabled"),
                resultSet.getString("revision")));
        List<EndpointAddressSnapshot> addresses = jdbcTemplate.query(
            "SELECT id, environment_code, endpoint_ref, protocol, host, port, base_path, enabled, revision,"
                + " credential_ref FROM iws_endpoint_address ORDER BY id",
            (resultSet, index) -> new EndpointAddressSnapshot(resultSet.getString("id"),
                resultSet.getString("environment_code"), resultSet.getString("endpoint_ref"),
                resultSet.getString("protocol"), resultSet.getString("host"),
                resultSet.getObject("port", Integer.class), resultSet.getString("base_path"),
                resultSet.getBoolean("enabled"), resultSet.getString("revision"),
                resultSet.getString("credential_ref")));
        List<EndpointSystemAddressSnapshot> links = jdbcTemplate.query(
            "SELECT system_code, address_id, enabled, revision FROM iws_endpoint_system_address ORDER BY id",
            (resultSet, index) -> new EndpointSystemAddressSnapshot(resultSet.getString("system_code"),
                resultSet.getString("address_id"), resultSet.getBoolean("enabled"),
                resultSet.getString("revision")));
        return new EndpointCatalogSnapshot(revision == null ? null : revision.toString(), systems, environments,
            addresses, links);
    }

    @Override
    public EndpointCatalogSnapshot lockSnapshot(String expectedCatalogRevision) {
        Long revision = jdbcTemplate.queryForObject(
            "SELECT revision FROM iws_endpoint_catalog_control WHERE singleton_key = 'CATALOG' FOR SHARE",
            Long.class);
        String actual = revision == null ? null : revision.toString();
        if (!java.util.Objects.equals(expectedCatalogRevision, actual)) {
            throw new EndpointCatalogConflictException(expectedCatalogRevision, actual);
        }
        return snapshot();
    }

    @Override
    public String currentEnvironmentCode() {
        return currentEnvironment;
    }
}
