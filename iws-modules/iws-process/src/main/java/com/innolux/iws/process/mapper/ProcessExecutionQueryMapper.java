/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行与请求记录查询的数据范围门禁 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.process.api.query.ProcessInstanceQueryRow;
import com.innolux.iws.process.api.query.ProcessAuditQuery;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordRow;
import com.innolux.iws.process.domain.ProcessActionAudit;
import com.innolux.iws.process.domain.ProcessEvent;
import com.innolux.iws.process.domain.ProcessInstance;
import org.apache.ibatis.annotations.Param;

/**
 * 计划执行查询的数据范围门禁 Mapper。
 *
 * <p>查询方法均带 {@link DataPermission}；有父对象的入口另经对象授权，未关联计划的请求记录
 * 使用自身来源范围。无角色、无当前用户或无业务范围时由公共数据权限处理器 fail closed。</p>
 */
public interface ProcessExecutionQueryMapper {

    /**
     * 按实例 ID 查询数据范围内实例。
     *
     * @param instanceId 实例 ID
     * @return 可见实例，不可见返回 null
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    ProcessInstance selectVisibleInstance(@Param("instanceId") long instanceId);

    /**
     * 已结束计划 SQL 分页。
     *
     * @param page 分页
     * @param scheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @param planType 计划类型
     * @param instanceId 实例 ID
     * @return 分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Page<ProcessInstance> selectExecutedOverviewPage(Page<ProcessInstance> page,
                                                     @Param("scheduleCode") String scheduleCode,
                                                     @Param("businessId") String businessId,
                                                     @Param("taskKey") String taskKey,
                                                     @Param("planType") String planType,
                                                     @Param("instanceId") String instanceId);

    /**
     * 未准入计划 SQL 分页，位次由数据库窗口函数计算。
     *
     * @param page 分页
     * @param scheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @param planType 计划类型
     * @param instanceId 实例 ID
     * @return 分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Page<ProcessInstanceQueryRow> selectPendingOverviewPage(Page<ProcessInstanceQueryRow> page,
                                                            @Param("scheduleCode") String scheduleCode,
                                                            @Param("businessId") String businessId,
                                                            @Param("taskKey") String taskKey,
                                                            @Param("planType") String planType,
                                                            @Param("instanceId") String instanceId);

    /**
     * 运行中计划 SQL 分页。
     *
     * @param page 分页
     * @param scheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @param planType 计划类型
     * @param instanceId 实例 ID
     * @return 分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Page<ProcessInstance> selectRunningOverviewPage(Page<ProcessInstance> page,
                                                    @Param("scheduleCode") String scheduleCode,
                                                    @Param("businessId") String businessId,
                                                    @Param("taskKey") String taskKey,
                                                    @Param("planType") String planType,
                                                    @Param("instanceId") String instanceId);

    /**
     * 异常/待人工计划 SQL 分页。
     *
     * @param page 分页
     * @param scheduleCode 业务调度编码
     * @param businessId 业务 ID
     * @param taskKey 任务键
     * @param planType 计划类型
     * @param instanceId 实例 ID
     * @return 分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Page<ProcessInstance> selectExceptionOverviewPage(Page<ProcessInstance> page,
                                                      @Param("scheduleCode") String scheduleCode,
                                                      @Param("businessId") String businessId,
                                                      @Param("taskKey") String taskKey,
                                                      @Param("planType") String planType,
                                                      @Param("instanceId") String instanceId);

    /**
     * 查询单个未准入实例在监督候选队列中的位次。
     *
     * @param scheduleCode 业务调度编码
     * @param instanceId 实例 ID
     * @return 位次；挂起或无候选返回 null
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Integer selectPendingRank(@Param("scheduleCode") String scheduleCode,
                              @Param("instanceId") long instanceId);

    /**
     * 按事件 ID 查询数据范围内事件。
     *
     * @param eventId 事件 ID
     * @return 可见事件，不可见返回 null
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    ProcessEvent selectVisibleEvent(@Param("eventId") long eventId);

    /**
     * 查询数据范围内请求方向记录安全分页，不读取请求或响应摘要。
     *
     * @param page 分页
     * @param query 筛选
     * @return 请求记录分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    Page<ProcessRequestRecordRow> selectRequestPage(Page<ProcessRequestRecordRow> page,
                                                    @Param("q") ProcessRequestQuery query);

    /**
     * 按本端请求 ID 查询数据范围内安全详情，不读取请求或响应摘要。
     *
     * @param requestId 本端请求 ID
     * @return 可见请求记录，不可见返回 null
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    ProcessRequestRecordRow selectVisibleRequest(@Param("requestId") String requestId);

    /**
     * 查询数据范围内审计分页。
     *
     * @param page 分页
     * @param query 筛选
     * @return 审计分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    Page<ProcessActionAudit> selectAuditPage(Page<ProcessActionAudit> page,
                                             @Param("q") ProcessAuditQuery query);
}
