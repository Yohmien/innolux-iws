/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则属性目录与生效配置只读查询服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service;

import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;

/**
 * 规则只读查询服务。
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleQueryService {

    /**
     * 分页查询启用状态的属性目录。
     *
     * @param pageQuery 分页参数
     * @return 属性目录分页
     */
    PageResult<WmsRuleAttributeVo> queryAttributes(PageQuery pageQuery);

    /**
     * 读取作用域的当前生效规则包与逻辑归属版本。
     *
     * <p>读取通过 {@code wms_rule_effective_pointer} 指针行与发布线性化：发布切换提交前开始的读取
     * 得到旧版本，提交后开始的读取得到新版本。</p>
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 生效配置
     */
    WmsRuleEffectiveVo getEffective(String scopeType, Long scopeId);

    /**
     * 读取发布命令切换后的当前生效规则包与逻辑归属版本。
     *
     * <p>仅供已完成命令级校验的发布流程在同一事务内读回使用；权限仍要求
     * {@code inventory:rule:publish}，不开放给外部查询入口。</p>
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 生效配置
     */
    WmsRuleEffectiveVo getEffectiveForPublish(String scopeType, Long scopeId);

    /**
     * 以冻结发起人身份读取发布后的生效事实。
     *
     * @param scopeType 作用域类型
     * @param scopeId 作用域内部 ID
     * @param actorId 冻结发起人用户 ID
     * @return 生效配置
     */
    default WmsRuleEffectiveVo getEffectiveForPublish(
        String scopeType,
        Long scopeId,
        Long actorId
    ) {
        return getEffectiveForPublish(scopeType, scopeId);
    }
}
