/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包版本 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsRulePackageVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 规则包版本 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsRulePackageVersionMapper
    extends BaseMapperPlus<WmsRulePackageVersion, WmsRulePackageVersion> {

    /**
     * 锁定同一规则包编码的最新版本行，串行分配包版本号。
     *
     * @param packageCode 规则包编码
     * @return 最新版本行，不存在时为 null
     */
    @Select("""
        select * from wms_rule_package_version
        where package_code = cast(#{packageCode} as binary)
        order by version_no desc limit 1
        for update
        """)
    WmsRulePackageVersion selectLatestByCodeForUpdate(@Param("packageCode") String packageCode);

    /**
     * 读取同一规则包编码的最大版本号。
     *
     * @param packageCode 规则包编码
     * @return 最大版本号，无版本时为 null
     */
    @Select("""
        select version_no from wms_rule_package_version
        where package_code = cast(#{packageCode} as binary)
        order by version_no desc limit 1
        """)
    Integer selectMaxVersionNo(@Param("packageCode") String packageCode);
}
