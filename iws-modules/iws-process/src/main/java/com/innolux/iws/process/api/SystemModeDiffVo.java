/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 逐系统模式前后差异；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.api;

/**
 * 逐系统模式差异。
 *
 * @param systemCode 系统编码
 * @param beforeMode 保存前模式；无政策归属时为 null
 * @param afterMode 待保存模式
 * @param changed 是否发生变化
 */
public record SystemModeDiffVo(String systemCode, String beforeMode, String afterMode, boolean changed) {
}
