/*
 * @Date         : 2026-11-02
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则发布计划
 * @Version      : V1.0
 * @Description  : 返回规则发布持久任务与计划实例身份；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.rule.process;

/**
 * 规则发布受理结果。
 *
 * @param planInstanceId 计划实例 ID
 * @param businessTaskId 业务任务 ID
 * @param inputHash 冻结输入摘要
 * @param queueStatus 挂载队列状态
 */
public record WmsRulePublicationStartResult(
    long planInstanceId,
    long businessTaskId,
    String inputHash,
    String queueStatus
) {
}
