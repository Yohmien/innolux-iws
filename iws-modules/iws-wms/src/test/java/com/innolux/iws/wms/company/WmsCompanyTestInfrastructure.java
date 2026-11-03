/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环集成测试
 * @Version      : V1.1
 * @Description  : 为提交异常分类与分页快照验证提供可注入的测试事务管理器和数据源；
 * @ModifyRecord : V1.0 新建；V1.1 增加事务回滚及失败留证查询顺序钩子；
 */

package com.innolux.iws.wms.company;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * 公司最小闭环测试基础设施。
 *
 * @author YONGJIE.PENG
 */
final class WmsCompanyTestInfrastructure {

    private WmsCompanyTestInfrastructure() {
    }

    /**
     * 可控的提交阶段故障模式。
     */
    enum CommitFault {
        NONE,
        COMMIT_THEN_THROW,
        ROLLBACK_THEN_THROW
    }

    /**
     * 只让下一次提交触发指定故障，故障后自动恢复，避免影响失败留证事务。
     */
    static final class FaultInjectingTransactionManager extends DataSourceTransactionManager {

        private final AtomicReference<CommitFault> fault = new AtomicReference<>(CommitFault.NONE);

        private final AtomicReference<Runnable> afterCommitThenThrow = new AtomicReference<>();

        private final AtomicReference<Runnable> afterRollback = new AtomicReference<>();

        FaultInjectingTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        void failNextCommit(CommitFault mode) {
            fault.set(mode);
        }

        /**
         * 在“提交成功但响应丢失”故障抛出前执行一次动作，用于制造提交后的并发撤权。
         *
         * @param action 提交后动作
         */
        void runAfterNextCommitThenThrow(Runnable action) {
            afterCommitThenThrow.set(action);
        }

        /**
         * 在下一次事务真实回滚后执行一次动作。
         *
         * @param action 回滚后动作
         */
        void runAfterNextRollback(Runnable action) {
            afterRollback.set(action);
        }

        void reset() {
            fault.set(CommitFault.NONE);
            afterCommitThenThrow.set(null);
            afterRollback.set(null);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            CommitFault mode = fault.getAndSet(CommitFault.NONE);
            if (mode == CommitFault.COMMIT_THEN_THROW) {
                super.doCommit(status);
                Runnable action = afterCommitThenThrow.getAndSet(null);
                if (action != null) {
                    action.run();
                }
                throw new TransactionSystemException("模拟提交成功但响应丢失");
            }
            if (mode == CommitFault.ROLLBACK_THEN_THROW) {
                super.doRollback(status);
                throw new TransactionSystemException("模拟提交失败并回滚");
            }
            super.doCommit(status);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            super.doRollback(status);
            Runnable action = afterRollback.getAndSet(null);
            if (action != null) {
                action.run();
            }
        }
    }

    /**
     * 在公司计数语句执行后注入一次动作，用于制造请求中途的授权提交。
     */
    static final class InterceptingDataSource implements DataSource {

        private final DataSource delegate;

        private final AtomicReference<Runnable> afterCompanyCount = new AtomicReference<>();

        private final AtomicReference<Runnable> beforeAttemptLookup = new AtomicReference<>();

        private final AtomicBoolean failNextAttemptLookup = new AtomicBoolean();

        InterceptingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void runAfterNextCompanyCount(Runnable action) {
            afterCompanyCount.set(action);
        }

        void failNextAttemptLookup() {
            failNextAttemptLookup.set(true);
        }

        void runBeforeNextAttemptLookup(Runnable action) {
            beforeAttemptLookup.set(action);
        }

        void reset() {
            afterCompanyCount.set(null);
            beforeAttemptLookup.set(null);
            failNextAttemptLookup.set(false);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrapConnection(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        private Connection wrapConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) {
                        return objectMethod(proxy, connection, method, args);
                    }
                    Object result = invoke(connection, method, args);
                    if ("prepareStatement".equals(method.getName())
                        && result instanceof PreparedStatement statement) {
                        return wrapStatement(statement, (String) args[0]);
                    }
                    if ("createStatement".equals(method.getName()) && result instanceof Statement statement) {
                        return wrapStatement(statement, null);
                    }
                    return result;
                }
            );
        }

        private Statement wrapStatement(Statement statement, String preparedSql) {
            Class<?>[] interfaces = statement instanceof PreparedStatement
                ? new Class<?>[]{PreparedStatement.class}
                : new Class<?>[]{Statement.class};
            return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                interfaces,
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) {
                        return objectMethod(proxy, statement, method, args);
                    }
                    String sql = preparedSql;
                    if (sql == null && args != null && args.length > 0 && args[0] instanceof String candidate) {
                        sql = candidate;
                    }
                    if (isAttemptLookupQuery(sql)) {
                        Runnable hook = beforeAttemptLookup.getAndSet(null);
                        if (hook != null) {
                            hook.run();
                        }
                        if (failNextAttemptLookup.getAndSet(false)) {
                            throw new SQLException("模拟提交结果复核查询失败");
                        }
                    }
                    Object result = invoke(statement, method, args);
                    if (method.getName().startsWith("execute") && isCompanyCountQuery(sql)) {
                        Runnable hook = afterCompanyCount.getAndSet(null);
                        if (hook != null) {
                            hook.run();
                        }
                    }
                    return result;
                }
            );
        }

        private static boolean isCompanyCountQuery(String sql) {
            if (sql == null) {
                return false;
            }
            String normalized = sql.toLowerCase(Locale.ROOT);
            return normalized.contains("count(") && normalized.contains("from wms_company");
        }

        private static boolean isAttemptLookupQuery(String sql) {
            if (sql == null) {
                return false;
            }
            String normalized = sql.toLowerCase(Locale.ROOT);
            return normalized.contains("from wms_command_attempt")
                && normalized.contains("select");
        }

        private static boolean isObjectMethod(Method method) {
            return method.getDeclaringClass() == Object.class;
        }

        private static Object objectMethod(Object proxy, Object target, Method method, Object[] args)
            throws Exception {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> target.toString();
                default -> method.invoke(target, args);
            };
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}
