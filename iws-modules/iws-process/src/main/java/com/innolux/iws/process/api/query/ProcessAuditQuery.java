/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 计划领域审计只读分页条件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 计划领域审计只读分页条件。
 *
 * @param businessScheduleCode 业务调度编码
 * @param instanceId 计划实例 ID
 * @param eventId 事件 ID
 * @param operator 操作者
 * @param action 动作编码
 * @param result 结果
 * @param occurredFrom 开始时间
 * @param occurredTo 结束时间
 * @param pageNum 页码，从 1 开始
 * @param pageSize 每页数量
 */
public record ProcessAuditQuery(String businessScheduleCode, String instanceId, String eventId, String operator,
                                String action, String result, String occurredFrom, String occurredTo,
                                Integer pageNum, Integer pageSize) {

    /**
     * 规整页码。
     *
     * @return 页码
     */
    public long normalizedPageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    /**
     * 规整每页数量，避免公共入口无界读取。
     *
     * @return 每页数量
     */
    public long normalizedPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }
}
