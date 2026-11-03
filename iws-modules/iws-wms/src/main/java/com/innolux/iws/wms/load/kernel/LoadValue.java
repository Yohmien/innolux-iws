/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核
 * @Version      : V1.0
 * @Description  : 封闭受信属性标量类型，拒绝任意对象进入装载汇合；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 适配器完成来源和类型校验后的受信规范化标量；本接口不解析文本或执行脚本。 */
public sealed interface LoadValue extends Comparable<LoadValue> permits
    LoadValue.TextValue, LoadValue.NumericValue, LoadValue.IntegerValue,
    LoadValue.BooleanValue, LoadValue.DateValue, LoadValue.InstantValue {

    Kind kind();

    static LoadValue text(String value) {
        return new TextValue(value);
    }

    static LoadValue decimal(BigDecimal value) {
        return new NumericValue(value);
    }

    static LoadValue integer(Long value) {
        return new IntegerValue(value);
    }

    static LoadValue bool(Boolean value) {
        return new BooleanValue(value);
    }

    static LoadValue date(LocalDate value) {
        return new DateValue(value);
    }

    static LoadValue instant(Instant value) {
        return new InstantValue(value);
    }

    @Override
    default int compareTo(LoadValue other) {
        if (kind() != other.kind()) {
            throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_TYPE_CONFLICT);
        }
        return switch (this) {
            case TextValue text -> compareText(text.value(), ((TextValue) other).value());
            case NumericValue numeric -> numeric.value().compareTo(((NumericValue) other).value());
            case IntegerValue integer -> integer.value().compareTo(((IntegerValue) other).value());
            case BooleanValue bool -> bool.value().compareTo(((BooleanValue) other).value());
            case DateValue date -> date.value().compareTo(((DateValue) other).value());
            case InstantValue instant -> instant.value().compareTo(((InstantValue) other).value());
        };
    }

    /** 精确字符串按 Unicode 码点排序，不归一化或修剪输入。 */
    static int compareText(String left, String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            int lc = left.codePointAt(li);
            int rc = right.codePointAt(ri);
            if (lc != rc) {
                return Integer.compare(lc, rc);
            }
            li += Character.charCount(lc);
            ri += Character.charCount(rc);
        }
        return Integer.compare(left.length() - li, right.length() - ri);
    }

    enum Kind {
        TEXT,
        DECIMAL,
        INTEGER,
        BOOLEAN,
        DATE,
        INSTANT
    }

    record TextValue(String value) implements LoadValue {
        public TextValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }

        @Override
        public Kind kind() {
            return Kind.TEXT;
        }
    }

    record NumericValue(BigDecimal value) implements LoadValue {
        public NumericValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
            value = value.stripTrailingZeros();
        }

        @Override
        public Kind kind() {
            return Kind.DECIMAL;
        }
    }

    record IntegerValue(Long value) implements LoadValue {
        public IntegerValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }

        @Override
        public Kind kind() {
            return Kind.INTEGER;
        }
    }

    record BooleanValue(Boolean value) implements LoadValue {
        public BooleanValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }

        @Override
        public Kind kind() {
            return Kind.BOOLEAN;
        }
    }

    record DateValue(LocalDate value) implements LoadValue {
        public DateValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }

        @Override
        public Kind kind() {
            return Kind.DATE;
        }
    }

    record InstantValue(Instant value) implements LoadValue {
        public InstantValue {
            if (value == null) {
                throw LoadKernelException.failure(LoadKernelException.Code.LOAD_ATTRIBUTE_INVALID);
            }
        }

        @Override
        public Kind kind() {
            return Kind.INSTANT;
        }
    }
}
