/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划已持久入站证据提供者；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.domain.PlanDefinition;

import java.util.List;

/**
 * 业务模块读取本库已提交入站证据并声明唯一归属。
 *
 * <p>实现只能返回已持久且仍未被实例/事件绑定的 {@code ProcessTriggerReceipt}，
 * 不得根据 hash、白名单或命令参数创建回执。平台会在准入事务内重新加锁核验。</p>
 */
public interface ImmediateInputEvidenceProvider {

    /**
     * 解析冻结版本本次命令可绑定的入站证据。
     *
     * @param definition 冻结业务定义
     * @param command 即时命令
     * @return 证据引用
     */
    List<ImmediateInputEvidenceRef> evidence(PlanDefinition definition, ExecuteImmediateCommand command);
}
