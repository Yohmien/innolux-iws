/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准导出请求 BO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准导出请求 BO。
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IwsExportRequestBo extends IwsTableQueryBo {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<Long> selectedIds = new ArrayList<>();

    private List<String> exportFields = new ArrayList<>();

    private String exportScope;
}
