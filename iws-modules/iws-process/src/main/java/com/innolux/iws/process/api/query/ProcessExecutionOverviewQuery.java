/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 计划执行查询
 * @Version      : V1.0
 * @Description  : 执行总览只读筛选条件；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api.query;

/**
 * 执行总览只读筛选条件。
 *
 * <p>业务调度编码是公共入口的必需范围；其余字段只缩小当前业务范围内的结果。</p>
 *
 * @param businessScheduleCode 业务调度编码
 * @param businessId 业务 ID
 * @param taskKey 任务唯一键
 * @param planType 计划类型
 * @param instanceId 计划实例 ID
 * @param executedPageNum 已结束页码
 * @param executedPageSize 已结束每页数量
 * @param pendingPageNum 未准入页码
 * @param pendingPageSize 未准入每页数量
 * @param runningPageNum 运行中页码
 * @param runningPageSize 运行中每页数量
 * @param exceptionPageNum 异常页码
 * @param exceptionPageSize 异常每页数量
 */
public record ProcessExecutionOverviewQuery(String businessScheduleCode, String businessId, String taskKey,
                                            String planType, String instanceId, Integer executedPageNum,
                                            Integer executedPageSize, Integer pendingPageNum,
                                            Integer pendingPageSize, Integer runningPageNum,
                                            Integer runningPageSize, Integer exceptionPageNum,
                                            Integer exceptionPageSize) {

    /**
     * 已结束分页。
     *
     * @return 分页规格
     */
    public PageSpec executedPage() {
        return PageSpec.of(executedPageNum, executedPageSize);
    }

    /**
     * 未准入门分页。
     *
     * @return 分页规格
     */
    public PageSpec pendingPage() {
        return PageSpec.of(pendingPageNum, pendingPageSize);
    }

    /**
     * 运行中分页。
     *
     * @return 分页规格
     */
    public PageSpec runningPage() {
        return PageSpec.of(runningPageNum, runningPageSize);
    }

    /**
     * 异常分页。
     *
     * @return 分页规格
     */
    public PageSpec exceptionPage() {
        return PageSpec.of(exceptionPageNum, exceptionPageSize);
    }

    /**
     * 单分类分页规格。
     *
     * @param pageNum 页码
     * @param pageSize 每页数量
     */
    public record PageSpec(long pageNum, long pageSize) {

        private static final long MAX_PAGE_SIZE = 200;

        /**
         * 规整分页参数，避免公共入口无界读取。
         *
         * @param pageNum 页码
         * @param pageSize 每页数量
         * @return 分页规格
         */
        public static PageSpec of(Integer pageNum, Integer pageSize) {
            long page = pageNum == null || pageNum < 1 ? 1 : pageNum;
            long size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, MAX_PAGE_SIZE);
            return new PageSpec(page, size);
        }
    }
}
