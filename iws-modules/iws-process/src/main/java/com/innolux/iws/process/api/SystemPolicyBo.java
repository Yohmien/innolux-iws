/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划配置与查询 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 逐系统模式保存参数。
 *
 * @param systemCode 系统编码
 * @param postingMode 过账模式
 * @param firstConfirmPolicy 首次确认策略
 * @param failurePolicy 失败策略
 * @param remark 说明
 */
public record SystemPolicyBo(String systemCode, String postingMode, String firstConfirmPolicy,
                             String failurePolicy, String remark) {
}
