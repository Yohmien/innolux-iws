/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则包生效指针 Mapper，提供发布排他与读取共享锁；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.mapper;

import com.innolux.iws.common.mybatis.core.mapper.BaseMapperPlus;
import com.innolux.iws.wms.rule.domain.WmsRuleEffectivePointer;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 规则包生效指针 Mapper。
 *
 * <p>发布使用 {@code for update} 排他锁切换指针；生效读取使用 {@code for share} 共享锁，使读取与
 * 发布在同一指针行上线性化：发布提交前开始的读取绑定旧版本，提交后开始的读取绑定新版本。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleEffectivePointerMapper
    extends BaseMapperPlus<WmsRuleEffectivePointer, WmsRuleEffectivePointer> {

    /**
     * 排他锁定作用域生效指针。
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 指针，尚未发布时为 null
     */
    @Select("""
        select * from wms_rule_effective_pointer
        where scope_type = cast(#{scopeType} as binary)
          and scope_id = #{scopeId}
        for update
        """)
    WmsRuleEffectivePointer selectByScopeForUpdate(@Param("scopeType") String scopeType,
                                                   @Param("scopeId") Long scopeId);

    /**
     * 共享锁定作用域生效指针。
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 指针，尚未发布时为 null
     */
    @Select("""
        select * from wms_rule_effective_pointer
        where scope_type = cast(#{scopeType} as binary)
          and scope_id = #{scopeId}
        for share
        """)
    WmsRuleEffectivePointer selectByScopeForShare(@Param("scopeType") String scopeType,
                                                  @Param("scopeId") Long scopeId);
}
