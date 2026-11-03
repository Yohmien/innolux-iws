/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : WMS 公司管理角色范围头 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.domain.WmsCompanyManagementScope;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WMS 公司管理角色范围头 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsCompanyManagementScopeMapper
    extends BaseMapperPlus<WmsCompanyManagementScope, WmsCompanyManagementScope> {

    /**
     * 锁定公司管理范围头。
     *
     * @param companyId 公司ID
     * @return 范围头，不存在时为 null
     */
    @Select("select * from wms_company_management_scope where company_id = #{companyId} for update")
    WmsCompanyManagementScope selectByIdForUpdate(@Param("companyId") Long companyId);

    /**
     * 共享锁定公司管理范围头，供持有业务事务的只读对象授权检查使用。
     *
     * @param companyId 公司ID
     * @return 范围头，不存在时为 null
     */
    @Select("select * from wms_company_management_scope where company_id = #{companyId} for share")
    WmsCompanyManagementScope selectByIdForShare(@Param("companyId") Long companyId);
}
