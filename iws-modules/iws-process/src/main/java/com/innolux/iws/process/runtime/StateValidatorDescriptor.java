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
 * 状态校验器描述。
 *
 * @param key 校验器键
 * @param version 校验器版本
 * @param displayName 中文名
 */
public record StateValidatorDescriptor(String key, String version, String displayName) {
}
