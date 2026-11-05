/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 已核验应用会话对应的菜单与页面上下文；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.domain;

import java.util.List;

/**
 * 应用页面上下文。
 *
 * @param issuer            权威签发方
 * @param userId            原系统用户主键
 * @param applicationId     目标应用标识
 * @param applicationType   应用类型
 * @param frontendId        受信前端入口
 * @param terminal          终端类型
 * @param sessionId         应用会话标识
 * @param sessionGeneration 应用会话代次
 * @param mappingVersion    菜单映射版本
 * @param items             当前账号实际可用页面
 */
public record IamViewContext(
    String issuer,
    Long userId,
    String applicationId,
    String applicationType,
    String frontendId,
    String terminal,
    String sessionId,
    long sessionGeneration,
    long mappingVersion,
    List<IamViewContextItem> items
) {
}
