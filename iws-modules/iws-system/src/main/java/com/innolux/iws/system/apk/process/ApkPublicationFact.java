/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布业务事实；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

/**
 * 已落库的 APK 发布事实。
 *
 * @param releaseId APK 版本 ID
 * @param planInstanceId process 计划实例 ID
 * @param published 是否已发布
 */
public record ApkPublicationFact(long releaseId, long planInstanceId, boolean published) {
}
