/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 逻辑归属 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsLogicalScope;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 逻辑归属 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsLogicalScopeMapper extends BaseMapperPlus<WmsLogicalScope, WmsLogicalScope> {

    /**
     * 按编码锁定并读取逻辑归属。
     *
     * @param scopeCode 逻辑归属编码
     * @return 逻辑归属，不存在时为 null
     */
    @Select("""
        select * from wms_logical_scope
        where scope_code = cast(#{scopeCode} as binary)
        for update
        """)
    WmsLogicalScope selectByCodeForUpdate(@Param("scopeCode") String scopeCode);

    /**
     * 按编码读取逻辑归属。
     *
     * @param scopeCode 逻辑归属编码
     * @return 逻辑归属，不存在时为 null
     */
    @Select("""
        select * from wms_logical_scope
        where scope_code = cast(#{scopeCode} as binary)
        """)
    WmsLogicalScope selectByCode(@Param("scopeCode") String scopeCode);
}
