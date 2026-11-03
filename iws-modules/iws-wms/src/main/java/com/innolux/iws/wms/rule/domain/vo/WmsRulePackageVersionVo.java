/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包版本视图；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则包版本视图。
 *
 * @param packageVersionId 规则包版本 ID
 * @param packageCode      规则包编码
 * @param versionNo        版本号
 * @param canonicalDigest  规范化内容摘要
 * @param status           发布状态
 * @param publishedAt      发布时间
 * @param publishedBy      发布人
 * @param members          成员内容
 * @author YONGJIE.PENG
 */
public record WmsRulePackageVersionVo(Long packageVersionId, String packageCode, Integer versionNo,
                                      String canonicalDigest, String status, LocalDateTime publishedAt,
                                      Long publishedBy, List<WmsRulePackageMemberVo> members) {
}
