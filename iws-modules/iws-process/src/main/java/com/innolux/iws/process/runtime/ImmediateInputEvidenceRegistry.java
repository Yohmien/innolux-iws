/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 即时计划已持久入站证据注册表；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.innolux.iws.process.api.ExecuteImmediateCommand;
import com.innolux.iws.process.domain.PlanDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 汇总业务证据提供者；不做默认补偿或白名单放行。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled",
    havingValue = "true")
public class ImmediateInputEvidenceRegistry {

    private final ObjectProvider<ImmediateInputEvidenceProvider> providers;

    /**
     * 构造注册表。
     *
     * @param providers 业务证据提供者
     */
    public ImmediateInputEvidenceRegistry(ObjectProvider<ImmediateInputEvidenceProvider> providers) {
        this.providers = providers;
    }

    /**
     * 汇总本次命令的证据引用。
     *
     * @param definition 冻结业务定义
     * @param command 即时命令
     * @return 不可变引用集合
     */
    public List<ImmediateInputEvidenceRef> resolve(PlanDefinition definition, ExecuteImmediateCommand command) {
        List<ImmediateInputEvidenceRef> values = new ArrayList<>();
        for (ImmediateInputEvidenceProvider provider : providers.orderedStream().toList()) {
            List<ImmediateInputEvidenceRef> evidence = provider.evidence(definition, command);
            if (evidence != null) {
                values.addAll(evidence);
            }
        }
        return List.copyOf(values);
    }
}
