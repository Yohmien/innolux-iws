/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 库存空间只读模型
 * @Version      : V1.0
 * @Description  : 空间主数据只读查询服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.bo.WmsStorageLocateBo;
import com.innolux.iws.wms.domain.vo.WmsStorageVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseVo;
import com.innolux.iws.wms.domain.vo.WmsWarehouseZoneVo;

/**
 * 空间主数据只读查询服务。
 *
 * @author YONGJIE.PENG
 */
public interface IWmsSpaceQueryService {

    /**
     * 分页查询当前公司管理范围内的仓库。
     *
     * @param queryBo 标准查询条件
     * @return 仓库分页
     */
    PageResult<WmsWarehouseVo> selectWarehousePage(IwsTableQueryBo queryBo);

    /**
     * 分页查询当前公司管理范围内的库区。
     *
     * @param queryBo 标准查询条件
     * @return 库区分页
     */
    PageResult<WmsWarehouseZoneVo> selectZonePage(IwsTableQueryBo queryBo);

    /**
     * 分页查询当前公司管理范围内的储位。
     *
     * @param queryBo 标准查询条件
     * @return 储位分页
     */
    PageResult<WmsStorageVo> selectStoragePage(IwsTableQueryBo queryBo);

    /**
     * 按仓库和五项地址精确定位储位。
     *
     * @param locateBo 精确定位请求
     * @return 0 或 1 条储位；S1 重复地址返回 {@code AMBIGUOUS}
     */
    PageResult<WmsStorageVo> locateStorage(WmsStorageLocateBo locateBo);
}
