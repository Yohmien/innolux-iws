/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划安全失败合同；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 一个精确动作版本在即时计划中的安全失败合同。
 *
 * @param operationKey 能力键，格式为 CALL:key:version 或 LOCAL:key:version
 * @param safeToFinalizeOnDeterministicFailure 确定失败且无在途副作用时是否可安全终结
 * @param recoveryInstruction 不能终结时必须遵守的中文恢复说明
 */
public record ImmediateFailureContract(String operationKey, boolean safeToFinalizeOnDeterministicFailure,
                                       String recoveryInstruction) {
}
