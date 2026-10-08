/*
 * @Date         : 2026-09-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 标准表数据页
 * @Version      : V1.0
 * @Description  : 标准分页查询 BO；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 标准分页查询 BO。
 *
 * @author YONGJIE.PENG
 */
@Data
public class IwsTableQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer pageNum = 1;

    private Integer pageSize = 50;

    private List<IwsQueryConditionBo> conditions = new ArrayList<>();

    private List<IwsBulkConditionBo> bulkConditions = new ArrayList<>();

    private List<IwsSortConditionBo> sorts = new ArrayList<>();
}
