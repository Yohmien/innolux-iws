/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 区分属性缺失、显式空值和受信标量值集合；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单个命名空间内一项属性的不可变事实快照。 */
public record LoadAttributeFact(String name, State state, List<LoadValue> values) {
    public LoadAttributeFact {
        if (name == null || name.isBlank() || state == null || values == null
            || values.stream().anyMatch(value -> value == null)
            || (state == State.PRESENT) != !values.isEmpty()) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
        }
        List<LoadValue> sorted = new ArrayList<>(values);
        if (sorted.stream().map(LoadValue::kind).distinct().count() > 1) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
        }
        sorted.sort(LoadValue::compareTo);
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).compareTo(sorted.get(index)) == 0) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }
        values = Collections.unmodifiableList(sorted);
    }

    public enum State {
        ABSENT,
        NULL,
        PRESENT
    }
}
