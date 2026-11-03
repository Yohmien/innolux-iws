/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划入站证据测试提供者；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime.immediate;

import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceProvider;
import com.innolux.iws.process.runtime.ImmediateInputEvidenceRef;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟业务模块从本库返回已持久回执 ID。
 */
public final class ImmediateInputEvidenceFixture implements ImmediateInputEvidenceProvider {

    private final AtomicReference<List<ImmediateInputEvidenceRef>> refs = new AtomicReference<>(List.of());

    /**
     * 设置本次准入可见证据。
     *
     * @param values 证据引用
     */
    public void set(List<ImmediateInputEvidenceRef> values) {
        refs.set(values == null ? List.of() : List.copyOf(values));
    }

    /**
     * 重置。
     */
    public void reset() {
        refs.set(List.of());
    }

    @Override
    public List<ImmediateInputEvidenceRef> evidence(PlanDefinition definition, ExecuteImmediateCommand command) {
        return refs.get();
    }
}
