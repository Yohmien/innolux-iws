/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则内容版本 Mapper；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsRuleVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 规则内容版本 Mapper。
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleVersionMapper extends BaseMapperPlus<WmsRuleVersion, WmsRuleVersion> {

    /**
     * 锁定并读取规则版本，供草稿编辑、激活和复制校验使用。
     *
     * @param ruleVersionId 规则版本 ID
     * @return 规则版本，不存在时为 null
     */
    @Select("select * from wms_rule_version where rule_version_id = #{ruleVersionId} for update")
    WmsRuleVersion selectByIdForUpdate(@Param("ruleVersionId") Long ruleVersionId);

    /**
     * 读取规则定义下最大版本号。
     *
     * @param ruleDefinitionId 规则定义 ID
     * @return 最大版本号，无版本时为 null
     */
    @Select("""
        select version_no from wms_rule_version
        where rule_definition_id = #{ruleDefinitionId}
        order by version_no desc limit 1
        """)
    Integer selectMaxVersionNo(@Param("ruleDefinitionId") Long ruleDefinitionId);
}
