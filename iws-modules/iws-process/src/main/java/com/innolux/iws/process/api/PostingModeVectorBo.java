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
 * 完整系统模式向量。
 *
 * @param expectedVersion 聚合版本
 * @param catalogRevision 目录版本
 * @param environmentCode 环境编码
 * @param commandKey 命令键
 * @param reason 原因
 * @param policies 完整系统模式集合
 */
public record PostingModeVectorBo(Long expectedVersion, String catalogRevision, String environmentCode,
                                  String commandKey, String reason, java.util.List<SystemPolicyBo> policies) {
}
