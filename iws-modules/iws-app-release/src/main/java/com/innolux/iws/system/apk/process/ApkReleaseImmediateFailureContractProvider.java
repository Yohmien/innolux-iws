/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : APK 发布即时计划安全失败合同；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.process.runtime.ImmediateFailureContract;
import com.innolux.iws.process.runtime.ImmediateFailureContractProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 声明 APK 本地发布动作的即时计划失败合同。
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkReleaseImmediateFailureContractProvider implements ImmediateFailureContractProvider {

    /**
     * 返回精确动作版本的合同。
     *
     * @return 单一安全失败合同
     */
    @Override
    public List<ImmediateFailureContract> contracts() {
        return List.of(new ImmediateFailureContract(
            "LOCAL:" + ApkProcessContract.LOCAL_ACTION_KEY + ":" + ApkProcessContract.VERSION,
            true,
            "APK 发布必须与业务发布状态同事务；端口异常时整体回滚，按 releaseId 对账后重试，禁止把未知结果改为成功。"));
    }
}
