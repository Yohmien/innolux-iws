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
 * 抛账模式影响预览。
 *
 * @param valid 是否通过
 * @param definitionRevision 定义版本
 * @param catalogRevision 目录版本
 * @param bypassSystems 将屏蔽系统
 * @param affectedEvents 受影响事件
 * @param unaffectedEvents 不受影响事件
 * @param errors 合同错误
 * @param environmentCode 当前环境
 * @param policies 待保存或已保存的完整模式向量
 * @param modeDiffs 逐系统模式差异
 */
public record PostingImpactVO(boolean valid, String definitionRevision, String catalogRevision,
                              java.util.List<String> bypassSystems, java.util.List<String> affectedEvents,
                              java.util.List<String> unaffectedEvents, java.util.List<String> errors,
                              String environmentCode, java.util.List<SystemPolicyBo> policies,
                              java.util.List<SystemModeDiffVo> modeDiffs) {
}
