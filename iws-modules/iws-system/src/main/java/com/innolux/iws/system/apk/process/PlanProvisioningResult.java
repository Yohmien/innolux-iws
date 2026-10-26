/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布计划初始化或核验结果；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

/**
 * APK 发布计划的初始化或核验结果。
 *
 * @param versionId 计划版本 ID
 * @param revision 计划聚合版本
 * @param planCode 计划编码
 * @param planType 计划类型
 * @param created 本次是否新建草稿
 * @param published 操作后是否为已发布状态
 */
public record PlanProvisioningResult(long versionId, long revision, String planCode, String planType,
                                     boolean created, boolean published, Long supervisorVersionId,
                                     Long supervisorRevision) {
    public PlanProvisioningResult(long versionId, long revision, String planCode, String planType,
                                  boolean created, boolean published) {
        this(versionId, revision, planCode, planType, created, published, null, null);
    }
}
