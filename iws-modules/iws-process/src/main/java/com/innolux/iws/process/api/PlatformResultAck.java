/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 平台结果回写确认；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 平台结果 CAS 回写确认。
 *
 * @param recorded 是否成功记录
 * @param conflict 是否因新控制修订号产生冲突
 * @param currentRevision 当前控制修订号
 * @param observedState 当前观测状态
 */
public record PlatformResultAck(boolean recorded, boolean conflict, long currentRevision, String observedState) {
}
