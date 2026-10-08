/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准用户列配置 BO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准用户列配置 BO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsColumnSettingBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String tableCode;

    private List<ColumnSettingItem> columns = new ArrayList<>();

    @Data
    public static class ColumnSettingItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String field;

        private Boolean visible;

        private Integer width;

        private String fixed;

        private Integer sortOrder;

        private Boolean searchable;

        private Boolean defaultSearchVisible;

        private String searchType;

        private String queryMode;

        private Boolean bulkSearch;

        private Boolean quickSearch;

        private Boolean sortable;

        private Boolean addable;

        private Boolean editable;

        private Boolean importable;

        private Boolean exportable;
    }
}
