/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 定义命令操作者、回执目标和稳定摘要；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 定义层命令信封工具。
 *
 * <p>回执唯一键由动作 + 目标 + commandKey 组成；目标中显式携带真实操作者，
 * 避免不同用户的同名随机键互相吞掉。公共 {@code CommandReceiptService} 保持原契约不变。</p>
 */
public final class DefinitionCommandSupport {

    private static final String SYSTEM_ACTOR = "system";

    private DefinitionCommandSupport() {
    }

    /**
     * 当前操作者稳定标识。
     *
     * @return 登录用户 ID；无登录上下文时为 system
     */
    public static String actor() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId != null && !loginId.toString().isBlank()) {
                return loginId.toString();
            }
        } catch (RuntimeException ignored) {
            // 测试或系统线程没有 Sa-Token 上下文。
        }
        return SYSTEM_ACTOR;
    }

    /**
     * 当前操作者用户 ID。
     *
     * @return 数字 ID，非数字或系统身份返回 null
     */
    public static Long actorUserId() {
        try {
            return Long.valueOf(actor());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 构造含操作者的回执目标。
     *
     * @param kind 命令类别
     * @param key 版本 ID 或计划编码
     * @return 回执目标
     */
    public static String target(String kind, String key) {
        return kind + ":" + key + ":actor:" + actor();
    }

    /**
     * 计算命令参数摘要。
     *
     * @param payload 参数对象
     * @return 十六进制摘要
     */
    public static String payloadHash(Object payload) {
        return ProcessText.sha256(String.valueOf(payload));
    }
}
