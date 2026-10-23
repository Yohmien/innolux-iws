/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 出入站请求方向记录只读分页条件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 出入站请求方向记录只读分页条件。
 *
 * <p>请求记录可能早于计划挂载或来自无法关联计划的历史来源；业务关联筛选只缩小结果，
 * 不把未关联记录排除在默认总览之外。</p>
 *
 * @param requestId 本端请求 ID
 * @param direction 请求方向
 * @param purpose 调用用途
 * @param contractKey 调用合同键
 * @param resultCode 接收方业务结果码
 * @param effectResult 效果判定
 * @param instanceId 计划实例 ID
 * @param eventId 事件 ID
 * @param correlationId 调用链标识
 * @param businessScheduleCode 业务调度编码
 * @param businessId 业务 ID
 * @param taskKey 任务唯一键
 * @param linked 是否已关联计划实例或事件
 * @param startedFrom 开始时间下限
 * @param startedTo 开始时间上限
 * @param pageNum 页码，从 1 开始
 * @param pageSize 每页数量
 */
public record ProcessRequestQuery(String requestId, String direction, String purpose, String contractKey,
                                  String resultCode, String effectResult, String instanceId, String eventId,
                                  String correlationId, String businessScheduleCode, String businessId,
                                  String taskKey, Boolean linked, String startedFrom, String startedTo,
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
