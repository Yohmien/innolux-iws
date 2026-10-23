/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 通用平台 scope 结果确认
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 通用平台结果确认。
 *
 * @param recorded 是否记录
 * @param conflict 是否与更新修订冲突
 * @param currentRevision 当前修订
 * @param observedState 当前观测
 * @param wakePending 当前是否待唤醒
 */
public record ControlIntentAck(boolean recorded, boolean conflict, long currentRevision,
                               String observedState, boolean wakePending) {
}
