/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 从物理权威账号库读取业务事务所需的账号、角色和权限事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.service;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权威身份事实 JDBC 读取器。
 *
 * <p>业务事务可能已经绑定业务库连接，因此这里使用 identity 物理数据源，
 * 并把只读事务保持到外层业务事务提交完成。写事务中共享锁可以阻止并发撤权或角色删除
 * 在业务提交前完成；业务事务已有旧快照时，这里仍会重新读取中心已提交事实。</p>
 *
 * <p>例外：认证中心本库 CRUD 的事务里 master 与 identity 是同一权威库。
 * 此时复用当前事务连接执行 FOR SHARE，避免独立连接锁住本事务即将更新的行。</p>
 */
@Component
public class JdbcIamIdentityFactReader implements IamIdentityFacts {

    private static final String EFFECTIVE_ROLE_PERMISSION = """
        from sys_user_role ur
        join sys_role r on r.role_id = ur.role_id and r.status = '0' and r.del_flag = '0'
        join sys_role_menu rm on rm.role_id = r.role_id
        join sys_menu m on m.menu_id = rm.menu_id
        """;

    private static final ThreadLocal<IdentitySession> CURRENT_SESSION = new ThreadLocal<>();

    private final DataSource identityDataSource;
    private final DynamicRoutingDataSource routingDataSource;
    private volatile String cachedAuthorityJdbcUrl;

    /**
     * 绑定动态数据源中的 identity 物理连接。
     *
     * @param dataSources 动态数据源
     */
    public JdbcIamIdentityFactReader(DynamicRoutingDataSource dataSources) {
        this.routingDataSource = dataSources;
        this.identityDataSource = dataSources.getDataSource("identity");
        if (this.identityDataSource == null) {
            throw new IllegalStateException("权威身份数据源 identity 未配置");
        }
    }

    @Override
    public UserFact findUser(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "select user_id, user_name, nick_name from sys_user where user_id = ?"
            )) {
                statement.setLong(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new UserFact(
                        result.getLong("user_id"),
                        result.getString("user_name"),
                        result.getString("nick_name")
                    );
                }
            }
        });
    }

    @Override
    public DeptFact findDept(Long deptId) {
        if (deptId == null || deptId <= 0) {
            return null;
        }
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "select dept_id, dept_name from sys_dept where dept_id = ?"
            )) {
                statement.setLong(1, deptId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new DeptFact(result.getLong("dept_id"), result.getString("dept_name"));
                }
            }
        });
    }

    @Override
    public List<Long> currentRoleIdsForShare(Long userId) {
        if (userId == null || userId <= 0) {
            return List.of();
        }
        return read(connection -> queryLongs(
            connection,
            """
            select r.role_id
            from sys_user_role ur
            join sys_role r on r.role_id = ur.role_id
            where ur.user_id = ?
              and r.status = '0'
              and r.del_flag = '0'
            order by r.role_id
            for share
            """,
            List.of(userId)
        ));
    }

    @Override
    public boolean currentPermission(Long userId, String permission) {
        return currentPermission(userId, permission, false);
    }

    @Override
    public boolean currentPermissionForShare(Long userId, String permission) {
        return currentPermission(userId, permission, true);
    }

    @Override
    public boolean superAdminForShare(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        return read(connection -> queryExists(
            connection,
            """
            select 1
            from sys_user_role ur
            join sys_role r on r.role_id = ur.role_id
            where ur.user_id = ?
              and r.role_key = 'superadmin'
              and r.status = '0'
              and r.del_flag = '0'
            limit 1
            for share
            """,
            List.of(userId)
        ));
    }

    @Override
    public List<Long> existingRoleIdsForShare(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> parameters = roleIds.stream().filter(id -> id != null && id > 0).toList();
        if (parameters.isEmpty()) {
            return List.of();
        }
        String placeholders = parameters.stream().map(ignored -> "?")
            .collect(Collectors.joining(","));
        return read(connection -> queryLongs(
            connection,
            "select role_id from sys_role where del_flag = '0' and role_id in ("
                + placeholders + ") order by role_id for share",
            parameters
        ));
    }

    @Override
    public List<Long> roleCustomDeptIdsForShare(Long roleId) {
        if (roleId == null || roleId <= 0) {
            return List.of();
        }
        return read(connection -> queryLongs(
            connection,
            """
            select dept_id
            from sys_role_dept
            where role_id = ?
            order by dept_id
            for share
            """,
            List.of(roleId)
        ));
    }

    @Override
    public List<Long> deptAndChildIdsForShare(Long deptId) {
        if (deptId == null || deptId <= 0) {
            return List.of();
        }
        return read(connection -> queryLongs(
            connection,
            """
            select dept_id
            from sys_dept
            where (dept_id = ?
                or find_in_set(?, ancestors))
              and del_flag = '0'
            order by dept_id
            for share
            """,
            List.of(deptId, deptId)
        ));
    }

    @Override
    public Map<Long, String> roleNames(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }
        List<Long> parameters = roleIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (parameters.isEmpty()) {
            return Map.of();
        }
        String placeholders = parameters.stream().map(ignored -> "?")
            .collect(Collectors.joining(","));
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                "select role_id, role_name from sys_role where del_flag = '0'"
                    + " and role_id in (" + placeholders + ")"
            )) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    Map<Long, String> values = new LinkedHashMap<>();
                    while (result.next()) {
                        values.put(result.getLong("role_id"), result.getString("role_name"));
                    }
                    return Map.copyOf(values);
                }
            }
        });
    }

    private boolean currentPermission(Long userId, String permission, boolean forShare) {
        if (userId == null || userId <= 0 || permission == null || permission.isBlank()) {
            return false;
        }
        String sql = "select 1 " + EFFECTIVE_ROLE_PERMISSION
            + " where ur.user_id = ? and m.status = '0' and m.perms = ? limit 1"
            + (forShare ? " for share" : "");
        return read(connection -> queryExists(connection, sql, List.of(userId, permission)));
    }

    private List<Long> queryLongs(Connection connection, String sql, List<?> parameters)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                List<Long> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getLong(1));
                }
                return List.copyOf(values);
            }
        }
    }

    private boolean queryExists(Connection connection, String sql, List<?> parameters)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void bind(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private <T> T read(IdentityRead<T> work) {
        IdentitySession session = acquireSession();
        try {
            return work.execute(session.connection());
        } catch (RuntimeException error) {
            session.failed = true;
            if (error instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("权威身份事实读取失败", 503);
        } catch (SQLException error) {
            session.failed = true;
            throw new ServiceException("权威身份事实读取失败", 503);
        } finally {
            if (!session.joinedTransaction) {
                finishSession(session, !session.failed);
            }
        }
    }

    private IdentitySession acquireSession() {
        IdentitySession existing = CURRENT_SESSION.get();
        if (existing != null) {
            return existing;
        }
        IdentitySession borrowed = borrowAuthorityTransactionConnection();
        if (borrowed != null) {
            return borrowed;
        }
        try {
            Connection connection = identityDataSource.getConnection();
            connection.setAutoCommit(false);
            boolean joined = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
            IdentitySession session = new IdentitySession(connection, joined);
            CURRENT_SESSION.set(session);
            if (joined) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public int getOrder() {
                            return Ordered.HIGHEST_PRECEDENCE;
                        }

                        @Override
                        public void afterCommit() {
                            finishSession(session, !session.failed);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (!session.closed) {
                                finishSession(
                                    session,
                                    status == TransactionSynchronization.STATUS_COMMITTED
                                        && !session.failed
                                );
                            }
                        }
                    }
                );
            }
            return session;
        } catch (SQLException error) {
            throw new ServiceException("权威身份数据源连接失败", 503);
        }
    }

    /**
     * 中心本库 CRUD 的事务里，identity 与主库是同一权威库；
     * 复用当前事务连接执行 FOR SHARE，避免独立连接锁住本事务即将更新的行。
     */
    private IdentitySession borrowAuthorityTransactionConnection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Object resource = TransactionSynchronizationManager.getResource(routingDataSource);
        if (!(resource instanceof ConnectionHolder holder)) {
            return null;
        }
        Connection txConnection = holder.getConnection();
        if (txConnection == null || !sameAuthorityConnection(txConnection)) {
            return null;
        }
        return new IdentitySession(txConnection, true, true);
    }

    private boolean sameAuthorityConnection(Connection txConnection) {
        String identityUrl = authorityJdbcUrl();
        if (identityUrl == null) {
            return false;
        }
        try {
            String txUrl = txConnection.getMetaData().getURL();
            return sameDatabase(identityUrl, txUrl);
        } catch (SQLException error) {
            return false;
        }
    }

    private String authorityJdbcUrl() {
        String cached = cachedAuthorityJdbcUrl;
        if (cached != null) {
            return cached;
        }
        String url = resolveDataSourceUrl(identityDataSource);
        if (url != null) {
            cachedAuthorityJdbcUrl = url;
        }
        return url;
    }

    private String resolveDataSourceUrl(DataSource dataSource) {
        DataSource current = dataSource;
        if (current instanceof com.baomidou.dynamic.datasource.ds.ItemDataSource item) {
            DataSource real = item.getRealDataSource();
            if (real != null) {
                current = real;
            }
        } else {
            try {
                DataSource unwrapped = current.unwrap(com.zaxxer.hikari.HikariDataSource.class);
                if (unwrapped != null) {
                    current = unwrapped;
                }
            } catch (SQLException ignored) {
                // 无法解包时返回 null，按独立连接语义处理。
            }
        }
        if (current instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            return hikari.getJdbcUrl();
        }
        return null;
    }

    private static boolean sameDatabase(String left, String right) {
        String leftBase = stripJdbcParameters(left);
        String rightBase = stripJdbcParameters(right);
        String leftServer = serverPart(leftBase);
        String rightServer = serverPart(rightBase);
        String leftCatalog = catalogPart(leftBase);
        String rightCatalog = catalogPart(rightBase);
        // MySQL 服务器与端口按大小写不敏感比较；catalog 在 lower_case_table_names=0
        // 下区分大小写，必须原样比较，不能把 iws 与 IWS 归并为同一库。
        return leftServer.equalsIgnoreCase(rightServer) && leftCatalog.equals(rightCatalog);
    }

    private static String stripJdbcParameters(String url) {
        int separator = url.indexOf('?');
        return separator >= 0 ? url.substring(0, separator) : url;
    }

    private static String serverPart(String base) {
        int slash = base.indexOf('/', "jdbc:mysql://".length());
        return slash >= 0 ? base.substring(0, slash) : base;
    }

    private static String catalogPart(String base) {
        int slash = base.indexOf('/', "jdbc:mysql://".length());
        return slash >= 0 ? base.substring(slash + 1) : "";
    }

    private void finishSession(IdentitySession session, boolean commit) {
        if (session.borrowedTransaction) {
            return;
        }
        if (session.closed) {
            return;
        }
        session.closed = true;
        if (CURRENT_SESSION.get() == session) {
            CURRENT_SESSION.remove();
        }
        try {
            if (commit) {
                session.connection().commit();
            } else {
                session.connection().rollback();
            }
        } catch (SQLException error) {
            throw new ServiceException("权威身份只读事务收口失败", 503);
        } finally {
            try {
                session.connection().close();
            } catch (SQLException ignored) {
                // 连接关闭失败不覆盖原业务结果；连接池会回收失效连接。
            }
        }
    }

    private static final class IdentitySession {
        private final Connection connection;
        private final boolean joinedTransaction;
        private final boolean borrowedTransaction;
        private boolean failed;
        private boolean closed;

        private IdentitySession(Connection connection, boolean joinedTransaction) {
            this(connection, joinedTransaction, false);
        }

        private IdentitySession(
            Connection connection,
            boolean joinedTransaction,
            boolean borrowedTransaction
        ) {
            this.connection = connection;
            this.joinedTransaction = joinedTransaction;
            this.borrowedTransaction = borrowedTransaction;
        }

        private Connection connection() {
            return connection;
        }
    }

    @FunctionalInterface
    private interface IdentityRead<T> {
        T execute(Connection connection) throws SQLException;
    }
}
