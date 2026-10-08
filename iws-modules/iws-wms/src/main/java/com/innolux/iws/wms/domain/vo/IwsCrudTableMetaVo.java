/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准表页面元数据 VO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.vo;

import com.innolux.iws.wms.domain.dto.IwsCrudCapabilityDto;
import com.innolux.iws.wms.domain.dto.IwsCrudColumnMetaDto;
import com.innolux.iws.wms.domain.dto.IwsCrudPermissionDto;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准表页面元数据 VO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsCrudTableMetaVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String tableCode;

    private String tableName;

    private String moduleCode;

    private String businessGroup;

    private String pageTitle;

    private String primaryKey;

    private String tableEngine = "el-table";

    private String defaultSort;

    private Integer pageSize = 50;

    private Integer maxPageSize = 1000;

    private Integer asyncExportThreshold = 50000;

    private IwsCrudCapabilityDto capabilities = new IwsCrudCapabilityDto();

    private IwsCrudPermissionDto permissions = new IwsCrudPermissionDto();

    private List<IwsCrudColumnMetaDto> columns = new ArrayList<>();
}
