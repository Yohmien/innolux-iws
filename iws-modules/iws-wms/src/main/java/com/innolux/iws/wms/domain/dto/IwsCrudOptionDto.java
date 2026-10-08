/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准字段选项 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标准字段选项 DTO，用于字典、下拉框和远程选项兜底展示。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsCrudOptionDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String label;

    private Object value;

    private Boolean disabled = Boolean.FALSE;

    private String tagType;

    public static IwsCrudOptionDto of(String label, Object value) {
        IwsCrudOptionDto option = new IwsCrudOptionDto();
        option.setLabel(label);
        option.setValue(value);
        return option;
    }
}
