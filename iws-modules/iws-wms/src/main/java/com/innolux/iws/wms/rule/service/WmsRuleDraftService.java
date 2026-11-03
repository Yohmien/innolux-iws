/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 规则定义与版本草稿管理服务；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.service;

import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionRetireCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDraftEditCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionActivateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionCopyCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;

/**
 * 规则定义与版本草稿管理服务。
 *
 * <p>草稿是唯一可变内容；ACTIVE 版本内容不可原位修改，RETIRED 版本只读。规则定义退役只停止新引用，
 * 不物理删除任何定义或版本。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsRuleDraftService {

    /**
     * 创建规则定义及其首个 DRAFT 版本。
     *
     * @param command 创建命令
     * @return 规则定义（含全部版本）
     */
    WmsRuleDefinitionVo createDefinition(WmsRuleDefinitionCreateCommand command);

    /**
     * 原位编辑 DRAFT 版本内容。
     *
     * @param command 编辑命令
     * @return 编辑后的版本
     */
    WmsRuleVersionVo editDraft(WmsRuleDraftEditCommand command);

    /**
     * 基于 ACTIVE 版本复制新 DRAFT 版本。
     *
     * @param command 复制命令
     * @return 新草稿版本
     */
    WmsRuleVersionVo copyDraft(WmsRuleVersionCopyCommand command);

    /**
     * 激活已校验且摘要未变的 DRAFT 版本。
     *
     * <p>发布规则包要求所引用版本为 ACTIVE，因此本命令是包发布前的独立步骤；它不切换生效指针，
     * 也不生成逻辑归属。</p>
     *
     * @param command 激活命令
     * @return 激活后的版本
     */
    WmsRuleVersionVo activateVersion(WmsRuleVersionActivateCommand command);

    /**
     * 退役规则定义，停止新引用。
     *
     * @param command 退役命令
     * @return 退役后的规则定义
     */
    WmsRuleDefinitionVo retireDefinition(WmsRuleDefinitionRetireCommand command);
}
