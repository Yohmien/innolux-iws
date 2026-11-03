/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 储位精确定位的单个地址字段条目；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import tools.jackson.databind.JsonNode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 储位精确定位的单个地址字段条目。
 *
 * <p>{@code value} 必须由调用方显式传入；显式 {@code null} 与缺键由
 * {@link #isValuePresent()} 区分。</p>
 *
 * @author YONGJIE.PENG
 */
public class WmsStorageAddressFieldBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String field;

    private JsonNode value;

    private boolean fieldPresent;

    private boolean valuePresent;

    private boolean duplicateFieldKey;

    private boolean duplicateValueKey;

    public String getField() {
        return field;
    }

    @JsonSetter("field")
    public void setField(String field) {
        if (fieldPresent) {
            duplicateFieldKey = true;
        }
        this.field = field;
        this.fieldPresent = true;
    }

    public JsonNode getValue() {
        return value;
    }

    /**
     * 接收显式 value，包括 JSON null。
     *
     * @param value 地址文本或 null
     */
    @JsonSetter("value")
    public void setValue(JsonNode value) {
        if (valuePresent) {
            duplicateValueKey = true;
        }
        this.value = value;
        this.valuePresent = true;
    }

    /**
     * 请求中是否出现了 value 键。
     *
     * @return true=显式传入，false=缺键
     */
    @JsonIgnore
    public boolean isValuePresent() {
        return valuePresent;
    }

    /**
     * 原始 JSON 是否重复出现 field 或 value 键。
     *
     * @return true=存在重复键
     */
    @JsonIgnore
    public boolean hasDuplicateKeys() {
        return duplicateFieldKey || duplicateValueKey;
    }

    /**
     * 原始 JSON 是否重复出现 value 键。
     *
     * @return true=value 重复
     */
    @JsonIgnore
    public boolean hasDuplicateValueKey() {
        return duplicateValueKey;
    }
}
