/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 自动空闲等领域枚举；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.enums;

/**
 * ControlState 稳定英文标识，中文展示由前端字典或内置映射提供。
 */
public enum ControlState {
    /** 自动空闲。 */
    AUTO_IDLE,
    /** 自动运行。 */
    AUTO_RUNNING,
    /** 人工暂停。 */
    MANUAL_PAUSED
}
