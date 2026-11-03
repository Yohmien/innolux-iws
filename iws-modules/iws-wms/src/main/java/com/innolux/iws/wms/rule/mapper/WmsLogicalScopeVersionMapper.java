/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 逻辑归属版本 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsLogicalScopeVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 逻辑归属版本 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsLogicalScopeVersionMapper
    extends BaseMapperPlus<WmsLogicalScopeVersion, WmsLogicalScopeVersion> {

    /**
     * 读取逻辑归属下最大版本号。
     *
     * @param logicalScopeId 逻辑归属 ID
     * @return 最大版本号，无版本时为 null
     */
    @Select("""
        select version_no from wms_logical_scope_version
        where logical_scope_id = #{logicalScopeId}
        order by version_no desc limit 1
        """)
    Integer selectMaxVersionNo(@Param("logicalScopeId") Long logicalScopeId);
}
