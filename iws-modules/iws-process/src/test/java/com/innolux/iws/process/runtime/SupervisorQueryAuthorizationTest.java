/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 验证监督列表复用多策略对象授权路由；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.process.api.SupervisorSummaryVO;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import com.innolux.iws.process.enums.PlanRole;
import com.innolux.iws.process.enums.PlanStatus;
import com.innolux.iws.process.mapper.PlanDefinitionMapper;
import com.innolux.iws.process.mapper.ProcessActionAuditMapper;
import com.innolux.iws.process.mapper.ProcessEventMapper;
import com.innolux.iws.process.mapper.ProcessInstanceMapper;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 监督列表对象授权测试。
 */
@Tag("local")
class SupervisorQueryAuthorizationTest {

    private ProcessInstanceMapper instanceMapper;

    private ProcessScheduleControlMapper controlMapper;

    private PlanDefinitionMapper definitionMapper;

    private ProcessEventMapper eventMapper;

    private ProcessActionAuditMapper auditMapper;

    private RuntimeDao runtimeDao;

    @BeforeEach
    void setUp() {
        ProcessTestAuth.install();
        instanceMapper = mock(ProcessInstanceMapper.class);
        controlMapper = mock(ProcessScheduleControlMapper.class);
        definitionMapper = mock(PlanDefinitionMapper.class);
        eventMapper = mock(ProcessEventMapper.class);
        auditMapper = mock(ProcessActionAuditMapper.class);
        runtimeDao = mock(RuntimeDao.class);
        when(controlMapper.selectCount(any())).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        ProcessTestAuth.clear();
    }

    @Test
    void listUsesAnyPolicyDecisionForEachSupervisor() {
        ProcessTestAuth.login();
        ProcessInstance apk = supervisor(10L, 1L, "APK");
        ProcessInstance wms = supervisor(20L, 1L, "WMS");
        when(instanceMapper.selectList(any())).thenReturn(List.of(apk, wms));
        when(runtimeDao.instance(10L)).thenReturn(apk);
        when(runtimeDao.instance(20L)).thenReturn(wms);
        ProcessObjectAuthorizer authorizer = authorizer(
            target -> target.instanceId() == 0L && target.scheduleId() == 1L,
            target -> "APK".equals(target.businessType()));
        SupervisorQueryService service = service(authorizer);

        PageResult<SupervisorSummaryVO> page = service.pageSupervisors(1L, null, null, null, null, pageQuery());

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRows()).extracting(SupervisorSummaryVO::supervisorInstanceId).containsExactly("10");
        assertThatCode(() -> authorizer.authorizeInstance(10L, "QUERY")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.authorizeInstance(20L, "QUERY"))
            .isInstanceOf(com.innolux.iws.common.core.exception.ServiceException.class);
    }

    @Test
    void actualSupervisorShapeSharesTargetAcrossListInstanceEventAndDispatch() {
        ProcessTestAuth.login();
        ProcessInstance supervisor = supervisor(10L, 1L, "WMS");
        assertThat(supervisor.getBusinessType()).isNull();
        assertThat(supervisor.getBusinessId()).isNull();
        when(instanceMapper.selectList(any())).thenReturn(List.of(supervisor));
        when(instanceMapper.selectById(10L)).thenReturn(supervisor);
        when(runtimeDao.instance(10L)).thenReturn(supervisor);
        when(runtimeDao.event(100L)).thenReturn(event(100L, 10L));
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(auditMapper.selectPage(any(), any())).thenReturn(new Page<ProcessActionAudit>());
        List<ProcessAccessTarget> targets = new CopyOnWriteArrayList<>();
        ProcessObjectAuthorizer authorizer = authorizer(target -> {
            targets.add(target);
            return target.instanceId() == 0L && target.scheduleId() == 1L
                || "WMS".equals(target.businessType());
        });
        SupervisorQueryService service = service(authorizer);

        PageResult<SupervisorSummaryVO> page = service.pageSupervisors(1L, null, null, null, null, pageQuery());
        service.detail(10L);
        authorizer.authorizeEvent(100L, "QUERY");
        PageResult<?> dispatch = service.pageDispatchRecords(10L, null, null, null, null, null,
            null, null, pageQuery());

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(dispatch.getTotal()).isZero();
        assertThat(targets).filteredOn(target -> target.instanceId() == 10L)
            .allSatisfy(target -> {
                assertThat(target.scheduleId()).isEqualTo(1L);
                assertThat(target.businessType()).isEqualTo("WMS");
                assertThat(target.businessId()).isEqualTo("SUPERVISOR:1:WMS:DEFAULT");
                assertThat(target.taskKey()).isNull();
                assertThat(target.scheduleScope()).isEqualTo("DEFAULT");
                assertThat(target.action()).isEqualTo("QUERY");
            });
        assertThat(targets).extracting(ProcessAccessTarget::eventId).containsNull().contains(100L);
    }

    @Test
    void listKeepsSuperObjectBypass() {
        ProcessTestAuth.login("process:object:all");
        when(instanceMapper.selectList(any())).thenReturn(List.of(
            supervisor(10L, 1L, "APK"), supervisor(20L, 1L, "WMS")));
        AtomicInteger calls = new AtomicInteger();
        ProcessObjectAuthorizer authorizer = authorizer(target -> {
            calls.incrementAndGet();
            return false;
        });
        SupervisorQueryService service = service(authorizer);

        PageResult<SupervisorSummaryVO> page = service.pageSupervisors(1L, null, null, null, null, pageQuery());

        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(calls).hasValue(0);
    }

    @SuppressWarnings("unchecked")
    private ProcessObjectAuthorizer authorizer(ProcessAccessPolicy... policies) {
        ObjectProvider<ProcessAccessPolicy> provider = mock(ObjectProvider.class);
        List<ProcessAccessPolicy> values = List.of(policies);
        when(provider.orderedStream()).thenAnswer(invocation -> values.stream());
        return new ProcessObjectAuthorizer(runtimeDao, provider);
    }

    private SupervisorQueryService service(ProcessObjectAuthorizer authorizer) {
        return new SupervisorQueryService(instanceMapper, controlMapper, definitionMapper,
            eventMapper, auditMapper, mock(JdbcTemplate.class), authorizer);
    }

    private static ProcessInstance supervisor(long id, long scheduleId, String businessType) {
        ProcessInstance instance = new ProcessInstance();
        instance.setId(id);
        instance.setPlanRole(PlanRole.SUPERVISOR);
        instance.setScheduleId(scheduleId);
        instance.setPlanType(businessType);
        instance.setSupervisorKey("SUPERVISOR:" + scheduleId + ":" + businessType + ":DEFAULT");
        instance.setScheduleScope("DEFAULT");
        instance.setPlanStatus(PlanStatus.RUNNING);
        return instance;
    }

    private static ProcessEvent event(long id, long instanceId) {
        ProcessEvent event = new ProcessEvent();
        event.setId(id);
        event.setInstanceId(instanceId);
        return event;
    }

    private static PageQuery pageQuery() {
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(20);
        return pageQuery;
    }
}
