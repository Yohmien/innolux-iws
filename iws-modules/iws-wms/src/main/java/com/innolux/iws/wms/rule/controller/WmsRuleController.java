/*
 * @Date         : 2026-10-28
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P3 规则服务
 * @Version      : V1.0
 * @Description  : 属性目录、规则草稿、规则包发布与生效读取入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.rule.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.wms.rule.WmsRuleContract;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDefinitionRetireCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleDraftEditCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackageCreateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRulePackagePublishCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionActivateCommand;
import com.innolux.iws.wms.rule.domain.command.WmsRuleVersionCopyCommand;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleAttributeVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleDefinitionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleEffectiveVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRulePackageVersionVo;
import com.innolux.iws.wms.rule.domain.vo.WmsRuleVersionVo;
import com.innolux.iws.wms.rule.process.WmsRulePublicationProcess;
import com.innolux.iws.wms.rule.process.WmsRulePublicationStartResult;
import com.innolux.iws.wms.rule.service.WmsRuleDraftService;
import com.innolux.iws.wms.rule.service.WmsRulePublishService;
import com.innolux.iws.wms.rule.service.WmsRuleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 在库管理规则服务入口。
 *
 * <p>读取使用 {@code inventory:rule:query}，草稿配置使用 {@code inventory:rule:draft}，版本激活与规则包
 * 发布使用 {@code inventory:rule:publish}。权限在服务层再次实时校验，注解不是唯一防线。</p>
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/wms/inventory/rule")
public class WmsRuleController {

    private final WmsRuleQueryService queryService;

    private final WmsRuleDraftService draftService;

    private final WmsRulePublishService publishService;

    private final WmsRulePublicationProcess publicationProcess;

    /**
     * 分页查询启用状态的属性目录。
     *
     * @param pageQuery 分页参数
     * @return 属性目录分页
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_QUERY)
    @GetMapping("/attributes")
    public R<PageResult<WmsRuleAttributeVo>> attributes(PageQuery pageQuery) {
        return R.ok(queryService.queryAttributes(pageQuery));
    }

    /**
     * 读取作用域的当前生效规则包与逻辑归属版本。
     *
     * @param scopeType 作用域类型
     * @param scopeId   作用域内部 ID
     * @return 生效配置
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_QUERY)
    @GetMapping("/effective")
    public R<WmsRuleEffectiveVo> effective(@RequestParam String scopeType,
                                           @RequestParam Long scopeId) {
        return R.ok(queryService.getEffective(scopeType, scopeId));
    }

    /**
     * 创建规则定义及其首个草稿版本。
     *
     * @param command 创建命令
     * @return 规则定义
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_DRAFT)
    @PostMapping("/definitions")
    public R<WmsRuleDefinitionVo> createDefinition(
        @RequestBody WmsRuleDefinitionCreateCommand command) {
        return R.ok(draftService.createDefinition(command));
    }

    /**
     * 原位编辑草稿版本。
     *
     * @param command 编辑命令
     * @return 编辑后的版本
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_DRAFT)
    @PutMapping("/versions/draft")
    public R<WmsRuleVersionVo> editDraft(@RequestBody WmsRuleDraftEditCommand command) {
        return R.ok(draftService.editDraft(command));
    }

    /**
     * 基于 ACTIVE 版本复制新草稿。
     *
     * @param command 复制命令
     * @return 新草稿版本
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_DRAFT)
    @PostMapping("/versions/copy")
    public R<WmsRuleVersionVo> copyDraft(@RequestBody WmsRuleVersionCopyCommand command) {
        return R.ok(draftService.copyDraft(command));
    }

    /**
     * 激活草稿版本。
     *
     * @param command 激活命令
     * @return 激活后的版本
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_PUBLISH)
    @PostMapping("/versions/activate")
    public R<WmsRuleVersionVo> activateVersion(
        @RequestBody WmsRuleVersionActivateCommand command) {
        return R.ok(draftService.activateVersion(command));
    }

    /**
     * 退役规则定义，停止新引用。
     *
     * @param command 退役命令
     * @return 退役后的规则定义
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_DRAFT)
    @PostMapping("/definitions/retire")
    public R<WmsRuleDefinitionVo> retireDefinition(
        @RequestBody WmsRuleDefinitionRetireCommand command) {
        return R.ok(draftService.retireDefinition(command));
    }

    /**
     * 创建规则包草稿版本。
     *
     * @param command 创建命令
     * @return 规则包版本
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_PUBLISH)
    @PostMapping("/packages")
    public R<WmsRulePackageVersionVo> createPackage(
        @RequestBody WmsRulePackageCreateCommand command) {
        return R.ok(publishService.createPackageVersion(command));
    }

    /**
     * 校验并发布规则包版本。
     *
     * @param command 发布命令
     * @return 持久计划实例与业务任务身份
     */
    @SaCheckPermission(WmsRuleContract.PERMISSION_PUBLISH)
    @PostMapping("/packages/publish")
    public R<WmsRulePublicationStartResult> publishPackage(
        @RequestBody WmsRulePackagePublishCommand command) {
        return R.ok(publicationProcess.start(command));
    }
}
