package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.ReceiveTriggerCommand;

/** 将既有认证上下文与来源合同绑定；不从 JSON 自报字段推导身份，缺适配时拒绝受理。 */
public interface InboundIdentityPolicy {
    /** 只读取可信接入上下文，不发网络请求、不执行业务动作。 */
    boolean accepts(ReceiveTriggerCommand command);
}
