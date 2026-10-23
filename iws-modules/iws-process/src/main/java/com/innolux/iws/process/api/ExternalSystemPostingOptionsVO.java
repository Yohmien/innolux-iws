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
 * 完整图外部系统汇总。
 *
 * @param definitionRevision 定义版本
 * @param catalogRevision 目录版本
 * @param environmentCode 当前环境
 * @param environmentRevision 当前环境对象版本
 * @param policySystems 有政策归属的系统
 * @param displaySystems 展示系统
 * @param asOf 生成时间
 */
public record ExternalSystemPostingOptionsVO(String definitionRevision, String catalogRevision,
                                             String environmentCode, String environmentRevision,
                                             java.util.List<ExternalSystemRowVO> policySystems,
                                             java.util.List<ExternalSystemRowVO> displaySystems,
                                             java.time.LocalDateTime asOf) {
}
