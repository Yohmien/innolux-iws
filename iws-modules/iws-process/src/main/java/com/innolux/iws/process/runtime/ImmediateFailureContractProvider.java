/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划安全失败合同提供者；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import java.util.List;

/**
 * 业务能力向即时计划声明安全失败合同。
 */
public interface ImmediateFailureContractProvider {

    /**
     * 当前提供者声明的合同。
     *
     * @return 合同集合
     */
    List<ImmediateFailureContract> contracts();
}
