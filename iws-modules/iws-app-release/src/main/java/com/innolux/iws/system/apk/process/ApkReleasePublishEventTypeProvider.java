/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 发布计划
 * @Version      : V1.0
 * @Description  : 注册 APK 发布 INTERNAL 事件类型；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.process;

import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.runtime.EventTypeDescriptor;
import com.innolux.iws.process.runtime.EventTypeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 仅登记 APK 发布的内部事件类型。
 */
@Component
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class ApkReleasePublishEventTypeProvider implements EventTypeProvider {

    /**
     * 返回稳定的事件类型描述。
     *
     * @return 单一 INTERNAL 事件类型
     */
    @Override
    public List<EventTypeDescriptor> describe() {
        return List.of(
            new EventTypeDescriptor(ApkProcessContract.EVENT_TYPE_KEY,
                ApkProcessContract.VERSION, ApkProcessContract.PLAN_NAME,
                Set.of(EventDirection.INTERNAL)),
            new EventTypeDescriptor(ApkProcessContract.SUPERVISOR_EVENT_TYPE_KEY,
                ApkProcessContract.VERSION, ApkProcessContract.SUPERVISOR_PLAN_NAME,
                Set.of(EventDirection.INTERNAL)));
    }
}
