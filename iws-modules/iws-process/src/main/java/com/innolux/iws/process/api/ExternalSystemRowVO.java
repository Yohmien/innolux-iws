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
 * 外部系统汇总行。
 *
 * @param systemCode 系统编码
 * @param displayName 中文名
 * @param environmentCode 环境
 * @param currentMode 当前模式
 * @param modeEditable 是否可编辑
 * @param policyOwner 是否政策归属
 * @param bypassableEvents 可屏蔽事件
 * @param unaffectedEvents 不受影响事件
 * @param disabledEvents 已禁用事件
 * @param endpointSummaries 地址摘要
 * @param reasons 原因
 * @param allowedModes 允许配置的模式
 * @param selfSystem 是否本系统
 * @param registered 是否已在目录登记
 */
public record ExternalSystemRowVO(String systemCode, String displayName, String environmentCode,
                                  String currentMode, boolean modeEditable, boolean policyOwner,
                                  java.util.List<String> bypassableEvents, java.util.List<String> unaffectedEvents,
                                  java.util.List<String> disabledEvents, java.util.List<String> endpointSummaries,
                                  java.util.List<String> reasons, java.util.List<String> allowedModes,
                                  boolean selfSystem, boolean registered) {
}
