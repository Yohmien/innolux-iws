/*
 * @Date         : 2026-10-27
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 公司最小闭环
 * @Version      : V1.0
 * @Description  : WMS 公司 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.domain.WmsCompany;
import com.innolux.iws.wms.domain.vo.WmsCompanyVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * WMS 公司 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsCompanyMapper extends BaseMapperPlus<WmsCompany, WmsCompanyVo> {

    /**
     * 锁定并读取目标公司，供修订 CAS 和撤权复核使用。
     *
     * @param companyId 公司ID
     * @return 公司实体，不存在时为 null
     */
    @Select("select * from wms_company where company_id = #{companyId} for update")
    WmsCompany selectByIdForUpdate(@Param("companyId") Long companyId);

    /**
     * 锁定首家公司或空表的首个主键间隙，串行化首家公司引导。
     *
     * @return 首家公司ID，空表时为 null
     */
    @Select("select company_id from wms_company order by company_id limit 1 for update")
    Long selectFirstCompanyIdForUpdate();
}
