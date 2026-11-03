/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : WMS 公司最小闭环服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.wms.domain.bo.IwsTableQueryBo;
import com.innolux.iws.wms.domain.dto.WmsCompanyCreateDTO;
import com.innolux.iws.wms.domain.dto.WmsCompanyEnableCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyManagementScopeReplaceCommand;
import com.innolux.iws.wms.domain.dto.WmsCompanyUpdateDTO;
import com.innolux.iws.wms.domain.vo.IwsCrudTableMetaVo;
import com.innolux.iws.wms.domain.vo.WmsCompanyManagementScopeVo;

import java.util.Map;

/**
 * WMS 公司最小闭环服务。
 *
 * @author YONGJIE.PENG
 */
public interface IWmsCompanyService {

    /**
     * 分页查询当前用户可见公司。
     *
     * <p>权限判定、计数与明细在同一个只读 REPEATABLE READ 快照内完成；
     * 请求开始后提交的撤权不改写本次的授权谓词与结果，下一次请求重新取快照后生效。</p>
     *
     * @param queryBo 查询条件
     * @return 分页结果
     */
    PageResult<Map<String, Object>> selectPage(IwsTableQueryBo queryBo);

    void create(WmsCompanyCreateDTO dto);

    void update(WmsCompanyUpdateDTO dto);

    void setEnabled(Long companyId, WmsCompanyEnableCommand command, boolean enabled);

    WmsCompanyManagementScopeVo queryManagementScope(Long companyId);

    WmsCompanyManagementScopeVo replaceManagementScope(
        Long companyId,
        WmsCompanyManagementScopeReplaceCommand command
    );

    IwsCrudTableMetaVo decorateMeta(IwsCrudTableMetaVo source);
}
