/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 逐系统模式原子保存结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 逐系统模式原子保存结果。
 *
 * @param valid 是否保存成功
 * @param definitionRevision 新定义版本
 * @param catalogRevision 目录版本
 * @param environmentCode 当前环境
 * @param policies 最终完整模式向量
 * @param bypassSystems 本次屏蔽系统
 * @param affectedEvents 本次零发送事件
 * @param unaffectedEvents 不受影响事件
 * @param errors 合同错误
 * @param modeDiffs 逐系统模式差异
 */
public record PostingPolicySaveResultVo(boolean valid, String definitionRevision, String catalogRevision,
                                        String environmentCode, java.util.List<SystemPolicyBo> policies,
                                        java.util.List<String> bypassSystems,
                                        java.util.List<String> affectedEvents,
                                        java.util.List<String> unaffectedEvents,
                                        java.util.List<String> errors,
                                        java.util.List<SystemModeDiffVo> modeDiffs) {
}
