/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准页面能力 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标准页面能力 DTO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsCrudCapabilityDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Boolean query = Boolean.TRUE;

    private Boolean add = Boolean.TRUE;

    private Boolean edit = Boolean.TRUE;

    private Boolean remove = Boolean.TRUE;

    private Boolean batchRemove = Boolean.TRUE;

    @JsonProperty("import")
    private Boolean importData = Boolean.TRUE;

    private Boolean export = Boolean.TRUE;

    private Boolean template = Boolean.TRUE;

    private Boolean column = Boolean.TRUE;

    private Boolean bulkQuery = Boolean.TRUE;
}
