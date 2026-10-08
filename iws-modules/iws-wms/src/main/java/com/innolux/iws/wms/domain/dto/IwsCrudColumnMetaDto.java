/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准字段元数据 DTO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准字段元数据 DTO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsCrudColumnMetaDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String tableCode;

    private String field;

    private String dbColumn;

    private String label;

    private String dataType;

    private Boolean visible = Boolean.TRUE;

    private Boolean defaultVisible = Boolean.TRUE;

    private Integer width;

    private Integer minWidth;

    private String fixed;

    private String align = "center";

    private Boolean showOverflowTooltip = Boolean.TRUE;

    private Boolean searchable = Boolean.FALSE;

    private Boolean defaultSearchVisible = Boolean.FALSE;

    private String searchType;

    private String queryMode;

    private Boolean bulkSearch = Boolean.FALSE;

    private Boolean quickSearch = Boolean.FALSE;

    private Boolean sortable = Boolean.FALSE;

    private String defaultSort;

    private Integer sortOrder;

    private Boolean addable = Boolean.TRUE;

    private Boolean editable = Boolean.TRUE;

    private Boolean importable = Boolean.TRUE;

    private Boolean exportable = Boolean.TRUE;

    private Boolean detailVisible = Boolean.TRUE;

    private String dictType;

    private String optionApi;

    private List<IwsCrudOptionDto> options = new ArrayList<>();

    private String permissionCode;

    private Boolean required = Boolean.FALSE;

    private Boolean readonly = Boolean.FALSE;

    private Boolean sensitive = Boolean.FALSE;
}
