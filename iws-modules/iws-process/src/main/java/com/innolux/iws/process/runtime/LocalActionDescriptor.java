/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 单动作与状态校验能力契约；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 本地动作能力描述。
 *
 * @param key 动作键
 * @param version 动作版本
 * @param displayName 中文名
 * @param transactionMode 事务模式说明
 * @param checkpointed 是否写可查询检查点
 */
public record LocalActionDescriptor(String key, String version, String displayName, String transactionMode,
                                    boolean checkpointed) {
}
