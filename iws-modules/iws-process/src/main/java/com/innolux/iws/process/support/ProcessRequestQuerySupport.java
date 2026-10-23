/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 请求记录来源范围与关联对象策略范围解析；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.api.query.ProcessRequestAccessScopeRow;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordRow;
import com.innolux.iws.process.mapper.ProcessRequestQueryMapper;
import com.innolux.iws.process.runtime.ProcessAccessTarget;
import com.innolux.iws.process.runtime.ProcessObjectAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 请求记录查询支持。
 *
 * <p>先用请求筛选和请求来源 SQL 取得候选关联对象，再以实例 SQL 数据范围和业务对象策略判定
 * 可见对象。分页与计数只接收判定后的对象 ID。</p>
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ProcessRequestQuerySupport {

    private static final int MAX_SCOPE_OBJECTS = 5_000;

    private static final String QUERY_ACTION = "QUERY";

    private final ProcessRequestQueryMapper requestQueryMapper;

    private final ProcessObjectAuthorizer objectAuthorizer;

    /**
     * 构造请求查询支持。
     *
     * @param requestQueryMapper 请求查询 Mapper
     * @param objectAuthorizer 业务对象授权
     */
    public ProcessRequestQuerySupport(ProcessRequestQueryMapper requestQueryMapper,
                                      ProcessObjectAuthorizer objectAuthorizer) {
        this.requestQueryMapper = requestQueryMapper;
        this.objectAuthorizer = objectAuthorizer;
    }

    /**
     * 查询来源范围与关联对象范围内的请求记录。
     *
     * @param query 规范化筛选条件
     * @return 请求记录分页
     */
    public Page<ProcessRequestRecordRow> selectPage(ProcessRequestQuery query) {
        List<Long> instanceCandidates = requestQueryMapper.selectRequestInstanceIds(query,
            MAX_SCOPE_OBJECTS + 1);
        List<Long> eventCandidates = requestQueryMapper.selectRequestEventIds(query,
            MAX_SCOPE_OBJECTS + 1);
        int scopeObjectCount = instanceCandidates.size() + eventCandidates.size();
        if (scopeObjectCount > MAX_SCOPE_OBJECTS) {
            throw new ServiceException("请求筛选涉及关联对象 "
                + scopeObjectCount + " 个，超过上限 " + MAX_SCOPE_OBJECTS + "，请增加筛选条件");
        }
        Set<Long> visibleInstanceIds = visibleInstanceIds(instanceCandidates);
        Set<Long> visibleEventIds = visibleEventIds(eventCandidates);
        Page<ProcessRequestRecordRow> page = new Page<>(query.normalizedPageNum(),
            query.normalizedPageSize());
        page.setOptimizeCountSql(false);
        return requestQueryMapper.selectRequestPage(page, query, visibleInstanceIds, visibleEventIds);
    }

    /**
     * 查询来源范围与关联对象范围内的请求详情。
     *
     * @param requestId 本端请求 ID
     * @return 可见请求记录，不可见返回 null
     */
    public ProcessRequestRecordRow selectVisibleRecord(String requestId) {
        ProcessRequestRecordRow row = requestQueryMapper.selectVisibleRequest(requestId);
        return row == null || !canAccess(row) ? null : row;
    }

    private Set<Long> visibleInstanceIds(List<Long> instanceIds) {
        if (instanceIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> visible = new HashSet<>();
        for (ProcessRequestAccessScopeRow row : requestQueryMapper.selectVisibleInstanceScopes(instanceIds)) {
            if (objectAuthorizer.canAccess(accessTarget(row, null))) {
                visible.add(row.getInstanceId());
            }
        }
        return Set.copyOf(visible);
    }

    private Set<Long> visibleEventIds(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> visible = new HashSet<>();
        for (ProcessRequestAccessScopeRow row : requestQueryMapper.selectVisibleEventScopes(eventIds)) {
            if (objectAuthorizer.canAccess(accessTarget(row, row.getEventId()))) {
                visible.add(row.getEventId());
            }
        }
        return Set.copyOf(visible);
    }

    private boolean canAccess(ProcessRequestRecordRow row) {
        if (row.getEventId() != null) {
            return !visibleEventIds(List.of(row.getEventId())).isEmpty();
        }
        if (row.getInstanceId() != null) {
            return !visibleInstanceIds(List.of(row.getInstanceId())).isEmpty();
        }
        return true;
    }

    private ProcessAccessTarget accessTarget(ProcessRequestAccessScopeRow row, Long eventId) {
        return new ProcessAccessTarget(row.getInstanceId(), eventId, row.getScheduleId(),
            row.getBusinessType(), row.getBusinessId(), row.getTaskKey(), row.getScheduleScope(),
            QUERY_ACTION);
    }
}
