/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型集成测试
 * @Version      : V1.0
 * @Description  : 为空间只读查询启动一次性 MySQL 容器；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.space;

import org.testcontainers.mysql.MySQLContainer;

/**
 * 空间只读查询隔离 MySQL 测试库。
 *
 * @author YONGJIE.PENG
 */
final class WmsSpaceTestDatabase {

    private static final String DATABASE_NAME = "iws_wms_space_test";

    private static final String DATABASE_USER = "iws_wms_space_test";

    private static final String DATABASE_PASSWORD = "iws_wms_space_test";

    private static final MySQLContainer MYSQL = startContainer();

    private WmsSpaceTestDatabase() {
    }

    static String jdbcUrl() {
        String url = MYSQL.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    }

    static String username() {
        return DATABASE_USER;
    }

    static String password() {
        return DATABASE_PASSWORD;
    }

    private static MySQLContainer startContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.4")
            .withDatabaseName(DATABASE_NAME)
            .withUsername(DATABASE_USER)
            .withPassword(DATABASE_PASSWORD)
            .withInitScript("mysql/wms-space-schema.sql");
        try {
            container.start();
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "空间只读集成测试需要可用的 Docker 与 mysql:8.4 镜像，当前环境无法启动测试库",
                error
            );
        }
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "wms-space-test-mysql-stop"));
        return container;
    }
}
