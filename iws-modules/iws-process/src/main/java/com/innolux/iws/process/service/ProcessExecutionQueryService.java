/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划执行、实例链、事件事实、请求明细和审计只读服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.process.api.query.EventExecutionVO;
import com.innolux.iws.process.api.query.EventRequestVO;
import com.innolux.iws.process.api.query.ExecutionOverviewVO;
import com.innolux.iws.process.api.query.PlanChainVO;
import com.innolux.iws.process.api.query.ProcessAuditEntryVO;
import com.innolux.iws.process.api.query.ProcessAuditQuery;
import com.innolux.iws.process.api.query.ProcessExecutionOverviewQuery;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordVO;

/**
 * 计划执行只读查询服务。
 *
 * <p>所有入口只读数据库已存事实；不重算并写状态、不发送 HTTP、不触发动作。</p>
 */
public interface ProcessExecutionQueryService {

    /**
     * 查询业务调度执行总览。
     *
     * @param query 筛选
     * @return 总览快照
     */
    ExecutionOverviewVO overview(ProcessExecutionOverviewQuery query);

    /**
     * 查询出入站请求方向记录安全总览。
     *
     * <p>未关联计划的记录同样可查，范围由请求记录自身的数据范围和可选业务筛选共同约束。</p>
     *
     * @param query 筛选
     * @return 请求记录分页，不包含请求或响应摘要
     */
    PageResult<ProcessRequestRecordVO> requestPage(ProcessRequestQuery query);

    /**
     * 查询单条请求方向记录安全详情。
     *
     * <p>不包含请求或响应摘要；原报文仍由独立事件请求权限入口读取。</p>
     *
     * @param requestId 本端请求 ID
     * @return 请求记录安全详情
     */
    ProcessRequestRecordVO requestRecord(String requestId);

    /**
     * 查询实例两级执行描述。
     *
     * @param instanceId 实例 ID
     * @return 链快照
     */
    PlanChainVO chain(long instanceId);

    /**
     * 查询单事件执行事实。
     *
     * @param eventId 事件 ID
     * @return 事件快照
     */
    EventExecutionVO event(long eventId);

    /**
     * 按权限查询单请求脱敏明细。
     *
     * @param eventId 事件 ID
     * @param requestId 请求 ID
     * @return 请求明细
     */
    EventRequestVO request(long eventId, String requestId);

    /**
     * 查询领域审计分页。
     *
     * @param query 筛选
     * @return 审计分页
     */
    PageResult<ProcessAuditEntryVO> audit(ProcessAuditQuery query);
}
