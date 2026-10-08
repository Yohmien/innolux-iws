/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准页面权限 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 标准页面权限 DTO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsCrudPermissionDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String list;

    private String query;

    private String add;

    private String edit;

    private String remove;

    private String batchRemove;

    @JsonProperty("import")
    private String importData;

    private String export;

    private String template;

    private String column;

    private String bulkQuery;
}
