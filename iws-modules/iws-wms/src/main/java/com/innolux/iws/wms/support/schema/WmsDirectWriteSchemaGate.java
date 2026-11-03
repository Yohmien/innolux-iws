/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 直接写入结构门禁
 * @Version      : V1.0
 * @Description  : 在公司和供应商直接写事务内锁定已验收结构登记并检查冻结运行；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.support.schema;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpStatus;
import com.innolux.iws.common.core.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 在写入事务内取得控制登记行的共享锁，并检查所有 WMS 控制运行。
 *
 * <p>共享锁持有到事务提交，使离线冻结 SQL 能在取得同一登记行排他锁后等待所有
 * 已受理写入结束。查询路径不调用本门禁。</p>
 *
 * @author YONGJIE.PENG
 */
@Slf4j
@Component
public class WmsDirectWriteSchemaGate {

    private static final String SCHEMA_QUERY = """
        select component_code, schema_version, release_id,
               source_fingerprint, target_fingerprint, status
          from wms_schema_version
         where component_code = ? and schema_version = ?
         for share
        """;

    private static final String RUN_QUERY = """
        select run_id, release_id, status, phase, target_fingerprint, completed_at
          from wms_upgrade_run
         order by run_id
         for share
        """;

    private static final String COMPLETION_REGISTRATION_QUERY = """
        select release_id, target_fingerprint
          from wms_schema_version
         where status = ?
         order by release_id, target_fingerprint, schema_version_id
         for share
        """;

    private final JdbcTemplate jdbcTemplate;

    public WmsDirectWriteSchemaGate(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 锁定直接写入结构登记并确认所有 WMS 控制运行均安全完成。
     *
     * <p>必须在公司和供应商的写入事务中、业务锁和命令 attempt 之前调用。</p>
     */
    public void acquire() {
        try {
            DirectWriteRegistration registration = loadRegistration();
            assertRegistration(registration);
            assertNotFenced();
        } catch (BadSqlGrammarException error) {
            throw unsupported(error);
        }
    }

    /**
     * 判断异常是否属于直接写入结构或冻结拒绝，供公司入口跳过失败 attempt 写入。
     *
     * @param error 事务体抛出的异常
     * @return true=属于受理前拒绝
     */
    public static boolean isPreAcceptanceRejection(RuntimeException error) {
        if (!(error instanceof ServiceException serviceException)) {
            return false;
        }
        String message = StrUtil.nullToEmpty(serviceException.getMessage());
        return message.startsWith(WmsDirectWriteSchemaContract.ERROR_SCHEMA_STATE_UNSUPPORTED + ":")
            || message.startsWith(WmsDirectWriteSchemaContract.ERROR_DIRECT_WRITE_FROZEN + ":")
            || message.startsWith(WmsDirectWriteSchemaContract.ERROR_DIRECT_WRITE_FENCE_CONFLICT + ":");
    }

    private DirectWriteRegistration loadRegistration() {
        List<DirectWriteRegistration> rows = jdbcTemplate.query(
            SCHEMA_QUERY,
            (resultSet, rowNum) -> new DirectWriteRegistration(
                resultSet.getString("component_code"),
                resultSet.getString("schema_version"),
                resultSet.getString("release_id"),
                resultSet.getString("source_fingerprint"),
                resultSet.getString("target_fingerprint"),
                resultSet.getString("status")
            ),
            WmsDirectWriteSchemaContract.COMPONENT_CODE,
            WmsDirectWriteSchemaContract.SCHEMA_VERSION
        );
        if (rows.size() != 1) {
            throw unsupported(null);
        }
        return rows.getFirst();
    }

    private void assertRegistration(DirectWriteRegistration registration) {
        if (!WmsDirectWriteSchemaContract.COMPONENT_CODE.equals(registration.componentCode())
            || !WmsDirectWriteSchemaContract.SCHEMA_VERSION.equals(registration.schemaVersion())
            || !WmsDirectWriteSchemaContract.RELEASE_ID.equals(registration.releaseId())
            || !WmsDirectWriteSchemaContract.STATUS_VERIFIED.equals(registration.status())
            || !WmsDirectWriteSchemaContract.TARGET_FINGERPRINT.equals(registration.sourceFingerprint())
            || !WmsDirectWriteSchemaContract.TARGET_FINGERPRINT.equals(registration.targetFingerprint())) {
            throw unsupported(null);
        }
    }

    private void assertNotFenced() {
        List<UpgradeRun> runs = jdbcTemplate.query(
            RUN_QUERY,
            (resultSet, rowNum) -> toUpgradeRun(resultSet)
        );
        Map<CompletionRegistration, Long> registrationCounts = jdbcTemplate.query(
            COMPLETION_REGISTRATION_QUERY,
            (resultSet, rowNum) -> new CompletionRegistration(
                resultSet.getString("release_id"),
                resultSet.getString("target_fingerprint")
            ),
            WmsDirectWriteSchemaContract.STATUS_VERIFIED
        ).stream().collect(Collectors.groupingBy(
            registration -> registration,
            Collectors.counting()
        ));
        List<UpgradeRun> unsafeRuns = runs.stream()
            .filter(run -> !isSafelyComplete(run, registrationCounts))
            .toList();
        if (unsafeRuns.size() > 1) {
            throw new ServiceException(
                WmsDirectWriteSchemaContract.ERROR_DIRECT_WRITE_FENCE_CONFLICT
                    + ": 存在多个未安全完成的 WMS 控制运行，拒绝已知直接写入",
                HttpStatus.HTTP_CONFLICT
            );
        }
        if (unsafeRuns.size() == 1) {
            UpgradeRun run = unsafeRuns.getFirst();
            throw new ServiceException(
                WmsDirectWriteSchemaContract.ERROR_DIRECT_WRITE_FROZEN
                    + ": WMS 控制运行 " + run.runId() + "（" + run.releaseId()
                    + "）未安全完成，拒绝已知直接写入",
                HttpStatus.HTTP_CONFLICT
            );
        }
    }

    private UpgradeRun toUpgradeRun(ResultSet resultSet) throws SQLException {
        return new UpgradeRun(
            resultSet.getLong("run_id"),
            resultSet.getString("release_id"),
            resultSet.getString("status"),
            resultSet.getString("phase"),
            resultSet.getString("target_fingerprint"),
            resultSet.getTimestamp("completed_at")
        );
    }

    private boolean isSafelyComplete(
        UpgradeRun run,
        Map<CompletionRegistration, Long> registrationCounts
    ) {
        CompletionRegistration registration = new CompletionRegistration(
            run.releaseId(),
            run.targetFingerprint()
        );
        return "COMPLETED".equals(run.status())
            && "COMPLETE".equals(run.phase())
            && run.completedAt() != null
            && registrationCounts.getOrDefault(registration, 0L) == 1L;
    }

    private ServiceException unsupported(BadSqlGrammarException cause) {
        ServiceException exception = new ServiceException(
            WmsDirectWriteSchemaContract.ERROR_SCHEMA_STATE_UNSUPPORTED
                + ": WMS 直接写入结构未完成可信登记或控制结构不可用",
            HttpStatus.HTTP_CONFLICT
        );
        if (cause != null) {
            exception.setDetailMessage(cause.getMessage());
            log.warn("WMS 直接写入控制结构不可用", cause);
        }
        return exception;
    }

    private record DirectWriteRegistration(
        String componentCode,
        String schemaVersion,
        String releaseId,
        String sourceFingerprint,
        String targetFingerprint,
        String status
    ) {
    }

    private record UpgradeRun(
        long runId,
        String releaseId,
        String status,
        String phase,
        String targetFingerprint,
        Timestamp completedAt
    ) {
    }

    private record CompletionRegistration(
        String releaseId,
        String targetFingerprint
    ) {
    }

}
