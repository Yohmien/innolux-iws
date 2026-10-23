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

import com.innolux.iws.process.enums.EventDirection;
import java.util.Set;

/**
 * 事件类型能力描述。
 *
 * @param key 稳定类型键
 * @param version 类型版本
 * @param displayName 中文名
 * @param supportedDirections 支持方向
 */
public record EventTypeDescriptor(String key, String version, String displayName,
                                  Set<EventDirection> supportedDirections) {
}
