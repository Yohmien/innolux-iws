/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 配置新增等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * ActionType 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum ActionType {
    /** 配置新增。 */
    DEFINITION_CREATE,
    /** 配置修改。 */
    DEFINITION_UPDATE,
    /** 配置发布。 */
    DEFINITION_PUBLISH,
    /** 配置复制。 */
    DEFINITION_COPY,
    /** 配置删除。 */
    DEFINITION_DELETE,
    /** 挂载。 */
    ATTACH,
    /** 触发。 */
    TRIGGER,
    /** 首次确认。 */
    CONFIRM_POSTING,
    /** 事件重试。 */
    EVENT_RETRY,
    /** 最终失败。 */
    EVENT_FINALIZE,
    /** 重新校验。 */
    EVENT_REVALIDATE,
    /** 执行。 */
    EVENT_EXECUTE,
    /** 跳过。 */
    EVENT_SKIP,
    /** 暂停。 */
    PLAN_PAUSE,
    /** 恢复。 */
    PLAN_RESUME,
    /** 取消。 */
    PLAN_CANCEL,
    /** 优先级。 */
    EVENT_PRIORITY,
    /** 调度控制。 */
    SCHEDULE_CONTROL,
    /** 命令拒绝。 */
    COMMAND_REJECTED
}
