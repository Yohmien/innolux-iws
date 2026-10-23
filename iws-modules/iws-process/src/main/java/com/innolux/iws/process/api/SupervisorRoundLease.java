/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督轮次租约；epoch/owner 为围栏身份
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

import java.time.LocalDateTime;

/**
 * 监督有限轮次租约。
 *
 * @param supervisorInstanceId 监督实例 ID
 * @param roundNo 轮次号
 * @param epoch 租约代次
 * @param owner 当前持有者
 * @param revision 开轮时控制修订号，仅用于观测
 * @param leaseUntil 租约到期时间
 */
public record SupervisorRoundLease(long supervisorInstanceId, long roundNo, long epoch, String owner,
                                   long revision, LocalDateTime leaseUntil) {
}
