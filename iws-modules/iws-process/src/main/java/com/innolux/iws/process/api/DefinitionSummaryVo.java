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
 * 计划版本摘要。
 *
 * @param versionId 版本 ID
 * @param planCode 计划编码
 * @param planVersion 业务版本
 * @param planName 计划名称
 * @param planType 计划类型
 * @param planRole 计划角色
 * @param status 版本状态
 * @param revision 聚合版本
 * @param latest 是否最新草稿
 * @param active 是否生效
 * @param publishedAt 发布时间
 */
public record DefinitionSummaryVo(String versionId, String planCode, Integer planVersion, String planName,
                                  String planType, String planRole, String status, String revision,
                                  Boolean latest, Boolean active, java.time.LocalDateTime publishedAt) {
}
