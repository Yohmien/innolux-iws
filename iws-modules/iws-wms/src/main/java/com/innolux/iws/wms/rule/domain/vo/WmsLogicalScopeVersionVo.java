/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 逻辑归属版本视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 逻辑归属版本视图。
 *
 * @param scopeVersionId        逻辑归属版本 ID
 * @param logicalScopeId        逻辑归属 ID
 * @param scopeCode             逻辑归属编码
 * @param versionNo             版本号
 * @param rulePackageVersionId  关联规则包版本 ID
 * @param spaceSnapshotDigest   空间快照摘要
 * @param status                状态
 * @param publishedAt           发布时间
 * @param publishedBy           发布人
 * @param storageIds            储位快照，升序
 * @author YONGJIE.PENG
 */
public record WmsLogicalScopeVersionVo(Long scopeVersionId, Long logicalScopeId, String scopeCode,
                                       Integer versionNo, Long rulePackageVersionId,
                                       String spaceSnapshotDigest, String status,
                                       LocalDateTime publishedAt, Long publishedBy,
                                       List<Long> storageIds) {
}
