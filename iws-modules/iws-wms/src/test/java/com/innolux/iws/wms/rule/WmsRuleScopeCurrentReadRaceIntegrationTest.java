/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 两连接验证候选解析后库区类型及父链变化不会被 RR 快照掩盖；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.rule.mapper.WmsRuleScopeMapper;
import com.innolux.iws.wms.rule.support.WmsRuleScopeGuard;
import com.innolux.iws.wms.support.company.WmsCompanyAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只代理候选读取的暂停时机，其余查询和锁均由真实 MySQL Mapper 执行。
 */
class WmsRuleScopeCurrentReadRaceIntegrationTest extends WmsRuleTestSupport {

    private static final int TIMEOUT_SECONDS = 15;

    @Autowired
    private WmsRuleScopeMapper scopeMapper;

    @Autowired
    private WmsCompanyAccessPolicy accessPolicy;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void deviceTargetConvertedToMaterialAfterCandidateIsRejected() throws Exception {
        assertConversionRejected(WmsRuleContract.SCOPE_DEVICE_ZONE, DEVICE_ZONE_ID,
            "DEVICE", MATERIAL_ZONE_ID, "MATERIAL", null,
            List.of(MATERIAL_ZONE_ID, DEVICE_ZONE_ID));
    }

    @Test
    void materialTargetConvertedToDeviceAfterCandidateIsRejected() throws Exception {
        long otherMaterial = insertOtherMaterial();
        assertConversionRejected(WmsRuleContract.SCOPE_MATERIAL_ZONE, MATERIAL_ZONE_ID,
            "MATERIAL", null, "DEVICE", otherMaterial, List.of(MATERIAL_ZONE_ID));
    }

    @Test
    void deviceTargetReparentedToUncollectedMaterialIsRejectedWithoutLockingNewParent()
        throws Exception {
        long otherMaterial = insertOtherMaterial();
        assertConversionRejected(WmsRuleContract.SCOPE_DEVICE_ZONE, DEVICE_ZONE_ID,
            "DEVICE", MATERIAL_ZONE_ID, "DEVICE", otherMaterial,
            List.of(MATERIAL_ZONE_ID, DEVICE_ZONE_ID));
    }

    private long insertOtherMaterial() {
        long zoneId = MATERIAL_ZONE_ID + 100;
        jdbcTemplate.update(
            "insert into wms_warehouse_zone "
                + "(zone_id, zone_code, zone_name, zone_type, warehouse_id, parent_zone_id, "
                + "zone_kind, lifecycle_status, del_flag) "
                + "values (?, 'M-RACE', '并发物料区', 1, ?, null, 'MATERIAL', 'ACTIVE', '0')",
            zoneId, SCOPE_ID
        );
        return zoneId;
    }

    private void assertConversionRejected(String type, long targetId,
                                          String initialKind, Long initialParent,
                                          String changedKind, Long changedParent,
                                          List<Long> expectedZoneLocks) throws Exception {
        for (boolean publishing : List.of(false, true)) {
            jdbcTemplate.update(
                "update wms_warehouse_zone set zone_kind = ?, parent_zone_id = ? where zone_id = ?",
                initialKind, initialParent, targetId
            );
            CountDownLatch candidateRead = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicInteger candidateReads = new AtomicInteger();
            AtomicReference<List<Long>> zoneLocks = new AtomicReference<>();
            WmsRuleScopeMapper pausedMapper = (WmsRuleScopeMapper) Proxy.newProxyInstance(
                WmsRuleScopeMapper.class.getClassLoader(),
                new Class<?>[] {WmsRuleScopeMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("lockZonesForShare")) {
                        Collection<?> ids = (Collection<?>) args[0];
                        zoneLocks.set(ids.stream().map(id -> (Long) id).toList());
                    }
                    Object result;
                    try {
                        result = method.invoke(scopeMapper, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                    if (method.getName().equals("selectTargetCandidate")) {
                        candidateReads.incrementAndGet();
                        candidateRead.countDown();
                        if (!resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new AssertionError("等待候选解析后的并发变更超时");
                        }
                    }
                    return result;
                }
            );
            WmsRuleScopeGuard guard = new WmsRuleScopeGuard(pausedMapper, accessPolicy);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var result = executor.submit(() -> {
                    WmsRuleTestAuth.loginAsAdmin();
                    try {
                        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                        transaction.executeWithoutResult(status -> {
                            accessPolicy.checkPermissionForShare(publishing
                                ? WmsRuleContract.PERMISSION_PUBLISH : WmsRuleContract.PERMISSION_QUERY);
                            if (publishing) {
                                guard.lockForPublish(type, targetId, List.of());
                            } else {
                                guard.lockForRead(type, targetId, List.of());
                            }
                        });
                        return (Throwable) null;
                    } catch (Throwable error) {
                        return error;
                    } finally {
                        WmsRuleTestAuth.clear();
                    }
                });
                try {
                    assertThat(candidateRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                    // 主测试线程另取连接提交变更；工作线程的 RR 读视图已经建立。
                    jdbcTemplate.update(
                        "update wms_warehouse_zone set zone_kind = ?, parent_zone_id = ? "
                            + "where zone_id = ?",
                        changedKind, changedParent, targetId
                    );
                    resume.countDown();
                    assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ServiceException.class, error ->
                            assertThat(error.getMessage()).startsWith(
                                WmsRuleContract.ERROR_SCOPE_PARENT_CHAIN_INVALID));
                    assertThat(candidateReads.get()).isEqualTo(1);
                    assertThat(zoneLocks.get()).containsExactlyElementsOf(
                        expectedZoneLocks.stream().sorted().toList());
                } finally {
                    resume.countDown();
                    executor.shutdownNow();
                }
            }
        }
    }
}
