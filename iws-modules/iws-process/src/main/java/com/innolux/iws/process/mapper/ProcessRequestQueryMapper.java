/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 请求记录来源范围与关联对象可见范围查询；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.innolux.iws.common.mybatis.annotation.DataColumn;
import com.innolux.iws.common.mybatis.annotation.DataPermission;
import com.innolux.iws.process.api.query.ProcessRequestAccessScopeRow;
import com.innolux.iws.process.api.query.ProcessRequestQuery;
import com.innolux.iws.process.api.query.ProcessRequestRecordRow;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 请求记录查询与对象范围 Mapper。
 *
 * <p>请求来源范围由请求记录自身的 {@link DataPermission} 过滤；关联实例和事件分别由实例表
 * 的 {@link DataPermission} 过滤。业务对象策略在该 SQL 结果之外由 Java 判定，不接收 SQL 片段。</p>
 */
public interface ProcessRequestQueryMapper {

    /**
     * 查询筛选范围内未关联事件请求引用的实例 ID。
     *
     * @param query 筛选条件
     * @param limit 最大读取数
     * @return 实例 ID
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    List<Long> selectRequestInstanceIds(@Param("q") ProcessRequestQuery query,
                                        @Param("limit") int limit);

    /**
     * 查询筛选范围内请求引用的事件 ID。
     *
     * @param query 筛选条件
     * @param limit 最大读取数
     * @return 事件 ID
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    List<Long> selectRequestEventIds(@Param("q") ProcessRequestQuery query,
                                     @Param("limit") int limit);

    /**
     * 查询数据范围内实例授权目标。
     *
     * @param instanceIds 实例 ID
     * @return 可见实例
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<ProcessRequestAccessScopeRow> selectVisibleInstanceScopes(
        @Param("instanceIds") Collection<Long> instanceIds);

    /**
     * 查询数据范围内事件及其实例授权目标。
     *
     * @param eventIds 事件 ID
     * @return 可见事件
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "i.create_dept"),
        @DataColumn(key = "userName", value = "i.create_by")
    })
    List<ProcessRequestAccessScopeRow> selectVisibleEventScopes(
        @Param("eventIds") Collection<Long> eventIds);

    /**
     * 查询来源范围和对象范围内的请求记录安全分页。
     *
     * @param page 分页
     * @param query 筛选条件
     * @param visibleInstanceIds 可见实例 ID
     * @param visibleEventIds 可见事件 ID
     * @return 请求记录分页
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    Page<ProcessRequestRecordRow> selectRequestPage(Page<ProcessRequestRecordRow> page,
                                                    @Param("q") ProcessRequestQuery query,
                                                    @Param("visibleInstanceIds") Collection<Long> visibleInstanceIds,
                                                    @Param("visibleEventIds") Collection<Long> visibleEventIds);

    /**
     * 按本端请求 ID 查询来源范围内安全详情。
     *
     * @param requestId 本端请求 ID
     * @return 来源范围内请求记录
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "r.create_dept"),
        @DataColumn(key = "userName", value = "r.create_by")
    })
    ProcessRequestRecordRow selectVisibleRequest(@Param("requestId") String requestId);
}
