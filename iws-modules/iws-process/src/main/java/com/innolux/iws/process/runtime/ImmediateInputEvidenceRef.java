/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划已持久入站证据引用；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

/**
 * 指向本库已持久、尚未绑定实例的入站回执。
 *
 * @param eventCode 冻结入站事件编码
 * @param receiptId 入站回执主键
 */
public record ImmediateInputEvidenceRef(String eventCode, long receiptId) {
}
