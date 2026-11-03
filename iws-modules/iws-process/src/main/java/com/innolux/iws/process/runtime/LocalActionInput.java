/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 本地动作从持久业务事实读取的冻结输入；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import java.util.Map;

/**
 * 本地动作冻结输入。
 *
 * @param payloadJson 完整业务命令 JSON
 * @param evidenceRef 冻结输入证据引用
 * @param facts 附加只读事实
 */
public record LocalActionInput(String payloadJson, String evidenceRef, Map<String, String> facts) {

    /**
     * 固化附加事实。
     */
    public LocalActionInput {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
