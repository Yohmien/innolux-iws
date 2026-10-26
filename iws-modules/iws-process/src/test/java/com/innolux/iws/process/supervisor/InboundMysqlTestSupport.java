/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 入站真实 MySQL 集成测试的隔离夹具与数据准备；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.supervisor;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.process.api.ReceiveTriggerCommand;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.EventRole;
import com.innolux.iws.process.enums.EventStatus;
import com.innolux.iws.process.enums.ExecutionPurpose;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PlanMode;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.enums.WaitReason;
import com.innolux.iws.process.mapper.ProcessEventDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入站集成测试公共数据与认证上下文。
 *
 * <p>只使用 13307 隔离 MySQL，并在每个用例前重建真实表结构。入站策略由测试声明为
 * Spring 扩展点，生产服务中的身份门禁、回执幂等、事件行锁与资格重算均按真实路径执行。</p>
 */
final class InboundMysqlTestSupport {

    static final long INSTANCE_ID = 9001L;

    static final long DEFINITION_ID = 8001L;

    static final long EVENT_DEFINITION_ID = 8101L;

    static final long EVENT_ID = 8201L;

    static final String EVENT_CODE = "RECEIVE";

    static final String CALLER_SYSTEM = "MID_WMS";

    static final String MODULE_CODE = "INBOUND";

    static final String CONTRACT_KEY = "INBOUND_RECEIVE";

    static final String CONTRACT_VERSION = "1";

    static final String EFFECT_KEY = "RECEIVE_BOX";

    private static final String REQUEST_DETAIL_MARKER = "CREATE TABLE iws_process_request_detail";

    private InboundMysqlTestSupport() {
    }

    /**
     * 重建监督隔离库，并补齐入站请求方向表。
     *
     * @param jdbcTemplate 隔离库 JDBC 入口
     * @throws Exception 建表失败
     */
    static void reset(JdbcTemplate jdbcTemplate) throws Exception {
        SupervisorMysqlFixture.resetSchema();
        recreateRequestDetail(jdbcTemplate);
    }

    /**
     * 安装每次测试独立的 Sa-Token 内存会话存储。
     */
    static void installSaToken() {
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        SaManager.setStpInterface(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object id, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object id, String loginType) {
                return List.of();
            }
        });
    }

    /**
     * 在调用线程建立真实 mock 上下文并登录。
     *
     * @param loginId 登录用户
     */
    static void login(long loginId) {
        SaTokenContextMockUtil.setMockContext();
        try {
            StpUtil.logout();
        } catch (RuntimeException ignored) {
            // 新线程尚无会话。
        }
        StpUtil.login(loginId);
    }

    /**
     * 清理当前线程的登录与 mock 上下文。
     */
    static void logoutAndClear() {
        try {
            if (StpUtil.isLogin()) {
                StpUtil.logout();
            }
        } catch (RuntimeException ignored) {
            // 测试上下文可能已清理。
        }
        SaTokenContextMockUtil.clearContext();
    }

    /**
     * 写入一个运行中的计划实例及其唯一入站事件合同。
     *
     * @param instanceMapper 实例 Mapper
     * @param definitionMapper 事件定义 Mapper
     * @param eventMapper 事件 Mapper
     */
    static void seedInbound(ProcessInstanceMapper instanceMapper,
                            ProcessEventDefinitionMapper definitionMapper,
                            ProcessEventMapper eventMapper) {
        LocalDateTime now = LocalDateTime.now();

        ProcessInstance instance = new ProcessInstance();
        instance.setId(INSTANCE_ID);
        instance.setPlanRole(PlanRole.BUSINESS);
        instance.setDefinitionVersionId(DEFINITION_ID);
        instance.setDefinitionRevision(1L);
        instance.setScheduleId(7001L);
        instance.setPlanType("INBOUND");
        instance.setBusinessScheduleCode("INBOUND");
        instance.setBusinessTaskKey("INBOUND:INC-1");
        instance.setBusinessType("INBOUND");
        instance.setBusinessId("INC-1");
        instance.setTaskKey("T-INC-1");
        instance.setScheduleScope("DEFAULT");
        instance.setModeSnapshot(PlanMode.QUEUE);
        instance.setHoldStart(Boolean.FALSE);
        instance.setPlanStatus(PlanStatus.RUNNING);
        instance.setInstancePriority(0);
        instance.setSelectedRootSet(EVENT_CODE);
        instance.setInputHash("seed-input");
        instance.setRoundNo(0L);
        instance.setStopRequested(Boolean.FALSE);
        instance.setCancelRequested(Boolean.FALSE);
        instance.setInstanceVersion(1L);
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        instanceMapper.insert(instance);

        ProcessEventDefinition definition = new ProcessEventDefinition();
        definition.setId(EVENT_DEFINITION_ID);
        definition.setDefinitionVersionId(DEFINITION_ID);
        definition.setEventCode(EVENT_CODE);
        definition.setDisplayName("入站收货");
        definition.setEventRole(EventRole.BUSINESS);
        definition.setEventDirection(EventDirection.INBOUND);
        definition.setCallContractKey(CONTRACT_KEY);
        definition.setCallContractVersion(CONTRACT_VERSION);
        definition.setOperationKind(OperationKind.INBOUND_TRIGGER);
        definition.setEffectKey(EFFECT_KEY);
        definition.setLocalEffectKey(EFFECT_KEY);
        definition.setSystemCode(CALLER_SYSTEM);
        definition.setPurpose(CallPurpose.TRIGGER_EVENT);
        definition.setSourceStateCode("RECEIVE_START");
        definition.setTargetStateCode("RECEIVE_DONE");
        definition.setEnabled(Boolean.TRUE);
        definition.setPriority(0);
        definitionMapper.insert(definition);

        ProcessEvent event = new ProcessEvent();
        event.setId(EVENT_ID);
        event.setInstanceId(INSTANCE_ID);
        event.setDefinitionVersionId(DEFINITION_ID);
        event.setEventDefinitionId(EVENT_DEFINITION_ID);
        event.setEventCode(EVENT_CODE);
        event.setEventKey("E:" + EVENT_CODE);
        event.setRoundNo(0L);
        event.setBranchIdentity("ROOT");
        event.setEventRole(EventRole.BUSINESS);
        event.setEventDirection(EventDirection.INBOUND);
        event.setSystemCode(CALLER_SYSTEM);
        event.setCallContractKey(CONTRACT_KEY);
        event.setCallContractVersion(CONTRACT_VERSION);
        event.setEffectKey(EFFECT_KEY);
        event.setLocalEffectKey(EFFECT_KEY);
        event.setSourceStateCode("RECEIVE_START");
        event.setTargetStateCode("RECEIVE_DONE");
        event.setEventStatus(EventStatus.WAIT_EXTERNAL_TRIGGER);
        event.setWaitReason(WaitReason.BUSINESS_TRIGGER);
        event.setPayloadReady(Boolean.FALSE);
        event.setIdempotencyKey("I:" + INSTANCE_ID + ":" + EVENT_CODE + ":"
            + CONTRACT_VERSION + ":" + EFFECT_KEY);
        event.setExecutionPurpose(ExecutionPurpose.NORMAL);
        event.setPriority(0);
        event.setRetryCount(0);
        event.setEventVersion(1L);
        event.setCreateTime(now);
        event.setUpdateTime(now);
        eventMapper.insert(event);
    }

    /**
     * 构造入站触发命令。
     *
     * @param sourceRequestId 来源请求 ID
     * @param effectKey 逻辑效果键
     * @param payloadHash 载荷摘要
     * @return 入站命令
     */
    static ReceiveTriggerCommand command(String sourceRequestId, String effectKey, String payloadHash) {
        return new ReceiveTriggerCommand(INSTANCE_ID, CALLER_SYSTEM, MODULE_CODE, CONTRACT_KEY,
            CONTRACT_VERSION, CallPurpose.TRIGGER_EVENT.name(), sourceRequestId, effectKey, payloadHash,
            "入站载荷摘要", null, "EXT-INC-1");
    }

    private static void recreateRequestDetail(JdbcTemplate jdbcTemplate) throws Exception {
        String schema = new ClassPathResource("process-query-test-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        int start = schema.indexOf(REQUEST_DETAIL_MARKER);
        int end = schema.indexOf(';', start);
        if (start < 0 || end < 0) {
            throw new IllegalStateException("process-query-test-schema.sql 缺少入站请求记录表");
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS iws_process_request_detail");
        jdbcTemplate.execute(schema.substring(start, end));
    }
}
