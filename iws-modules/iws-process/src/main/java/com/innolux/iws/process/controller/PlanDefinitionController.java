/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划版本与图配置入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.process.api.CapabilityOptionsVO;
import com.innolux.iws.process.api.DefinitionCommandRequest;
import com.innolux.iws.process.api.DefinitionDetailVo;
import com.innolux.iws.process.api.DefinitionSummaryVo;
import com.innolux.iws.process.api.DefinitionValidationVo;
import com.innolux.iws.process.api.DraftCreateRequest;
import com.innolux.iws.process.api.DraftSaveRequest;
import com.innolux.iws.process.api.EditCapabilitiesVo;
import com.innolux.iws.process.api.EventSaveRequest;
import com.innolux.iws.process.api.ExternalSystemPostingOptionsVO;
import com.innolux.iws.process.api.PostingImpactVO;
import com.innolux.iws.process.api.PostingModeVectorBo;
import com.innolux.iws.process.api.PostingPolicySaveResultVo;
import com.innolux.iws.process.api.StateSaveRequest;
import com.innolux.iws.process.api.SystemPolicyBo;
import com.innolux.iws.process.runtime.CapabilityRegistry;
import com.innolux.iws.process.service.PlanDefinitionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划版本配置入口。
 */
@Validated
@RestController
@RequestMapping("/process/definition")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class PlanDefinitionController {

    private final PlanDefinitionService planDefinitionService;

    private final CapabilityRegistry capabilityRegistry;

    /**
     * 构造控制器。
     *
     * @param planDefinitionService 定义服务
     * @param capabilityRegistry 能力注册表
     */
    public PlanDefinitionController(PlanDefinitionService planDefinitionService,
                                    CapabilityRegistry capabilityRegistry) {
        this.planDefinitionService = planDefinitionService;
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 分页查询版本。
     *
     * @param planCode 计划编码
     * @param status 状态
     * @param planRole 角色
     * @param pageQuery 分页
     * @return 分页结果
     */
    @SaCheckPermission("process:definition:list")
    @GetMapping("/page")
    public R<PageResult<DefinitionSummaryVo>> page(@RequestParam(required = false) String planCode,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String planRole,
                                                   PageQuery pageQuery) {
        return R.ok(planDefinitionService.page(planCode, status, planRole, pageQuery));
    }

    /**
     * 版本详情。
     *
     * @param versionId 版本 ID
     * @return 详情
     */
    @SaCheckPermission("process:definition:query")
    @GetMapping("/{versionId}")
    public R<DefinitionDetailVo> get(@PathVariable long versionId) {
        return R.ok(planDefinitionService.get(versionId));
    }

    /**
     * 编辑能力。
     *
     * @param versionId 版本 ID
     * @return 编辑能力
     */
    @SaCheckPermission("process:definition:query")
    @GetMapping("/{versionId}/edit-capabilities")
    public R<EditCapabilitiesVo> editCapabilities(@PathVariable long versionId) {
        return R.ok(planDefinitionService.editCapabilities(versionId));
    }

    /**
     * 已注册能力目录。
     *
     * @return 能力目录
     */
    @SaCheckPermission("process:definition:query")
    @GetMapping("/options")
    public R<CapabilityOptionsVO> options() {
        return R.ok(new CapabilityOptionsVO(capabilityRegistry.eventTypes(), capabilityRegistry.callContracts(),
            capabilityRegistry.localActions(), capabilityRegistry.validators(), capabilityRegistry.conditions()));
    }

    /**
     * 创建初始草稿。
     *
     * @param request 请求
     * @return 详情
     */
    @SaCheckPermission("process:definition:add")
    @Log(title = "计划定义", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/draft")
    public R<DefinitionDetailVo> createDraft(@RequestBody DraftCreateRequest request) {
        return R.ok(planDefinitionService.createDraft(request.draft(), request.commandKey(), request.reason()));
    }

    /**
     * 完整草稿原子保存。
     *
     * @param versionId 版本 ID
     * @param request 请求
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划定义", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{versionId}")
    public R<DefinitionDetailVo> saveDraft(@PathVariable long versionId, @RequestBody DraftSaveRequest request) {
        return R.ok(planDefinitionService.saveDraft(versionId, request.draft(), request.expectedVersion(),
            request.commandKey(), request.reason()));
    }

    /**
     * 保存基本信息。
     *
     * @param versionId 版本 ID
     * @param request 请求
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划定义", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{versionId}/base")
    public R<DefinitionDetailVo> saveBase(@PathVariable long versionId, @RequestBody DraftSaveRequest request) {
        return R.ok(planDefinitionService.saveBase(versionId, request.draft(), request.expectedVersion(),
            request.commandKey(), request.reason()));
    }

    /**
     * 保存单个状态。
     *
     * @param versionId 版本 ID
     * @param stateCode 状态编码
     * @param request 请求
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划状态", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{versionId}/states/{stateCode}")
    public R<DefinitionDetailVo> saveState(@PathVariable long versionId, @PathVariable String stateCode,
                                           @RequestBody StateSaveRequest request) {
        if (request.state() == null) {
            return R.fail("状态不能为空");
        }
        if (!stateCode.equals(request.state().stateCode())) {
            return R.fail("路径状态编码与请求体不一致");
        }
        return R.ok(planDefinitionService.saveState(versionId, request.state(), request.expectedVersion(),
            request.commandKey(), request.reason()));
    }

    /**
     * 保存单个事件及关联边。
     *
     * @param versionId 版本 ID
     * @param eventCode 事件编码
     * @param request 请求
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划事件", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{versionId}/events/{eventCode}")
    public R<DefinitionDetailVo> saveEvent(@PathVariable long versionId, @PathVariable String eventCode,
                                           @RequestBody EventSaveRequest request) {
        if (request.patch() == null || request.patch().event() == null) {
            return R.fail("事件补丁不能为空");
        }
        if (!eventCode.equals(request.patch().event().eventCode())) {
            return R.fail("路径事件编码与请求体不一致");
        }
        return R.ok(planDefinitionService.saveEvent(versionId, request.patch(), request.commandKey(),
            request.reason()));
    }

    /**
     * 删除最新草稿事件。
     *
     * @param versionId 版本 ID
     * @param eventCode 事件编码
     * @param expectedVersion 版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划事件", businessType = BusinessType.DELETE)
    @RepeatSubmit()
    @DeleteMapping("/{versionId}/events/{eventCode}")
    public R<DefinitionDetailVo> deleteEvent(@PathVariable long versionId, @PathVariable String eventCode,
                                             @RequestParam long expectedVersion,
                                             @RequestParam String commandKey,
                                             @RequestParam String reason) {
        return R.ok(planDefinitionService.deleteEvent(versionId, eventCode, expectedVersion, commandKey, reason));
    }

    /**
     * 原子保存完整系统模式向量。
     *
     * @param versionId 版本 ID
     * @param vector 模式向量
     * @return 详情
     */
    @SaCheckPermission("process:definition:edit")
    @Log(title = "计划系统模式", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{versionId}/system-policies")
    public R<PostingPolicySaveResultVo> saveSystemPolicies(@PathVariable long versionId,
                                                           @RequestBody PostingModeVectorBo vector) {
        return R.ok(planDefinitionService.saveSystemPolicies(versionId, vector));
    }

    /**
     * 完整图外部系统汇总。
     *
     * @param versionId 版本 ID
     * @return 系统行与当前环境地址摘要
     */
    @SaCheckPermission("process:definition:query")
    @GetMapping("/{versionId}/external-systems")
    public R<ExternalSystemPostingOptionsVO> externalSystems(@PathVariable long versionId) {
        return R.ok(planDefinitionService.externalSystems(versionId));
    }

    /**
     * 系统模式影响预览；纯本库校验，不探活、不发送。
     *
     * @param versionId 版本 ID
     * @param vector 完整模式向量
     * @return 影响报告
     */
    @SaCheckPermission("process:definition:query")
    @PostMapping("/{versionId}/system-policies/preview")
    public R<PostingImpactVO> previewSystemPolicies(@PathVariable long versionId,
                                                    @RequestBody PostingModeVectorBo vector) {
        return R.ok(planDefinitionService.previewSystemPolicies(versionId, vector));
    }

    /**
     * 查询系统模式。
     *
     * @param versionId 版本 ID
     * @return 系统模式
     */
    @SaCheckPermission("process:definition:query")
    @GetMapping("/{versionId}/system-policies")
    public R<List<SystemPolicyBo>> systemPolicies(@PathVariable long versionId) {
        return R.ok(planDefinitionService.systemPolicies(versionId));
    }

    /**
     * 校验草稿。
     *
     * @param versionId 版本 ID
     * @return 校验结果
     */
    @SaCheckPermission("process:definition:publish")
    @PostMapping("/{versionId}/validate")
    public R<DefinitionValidationVo> validate(@PathVariable long versionId) {
        return R.ok(planDefinitionService.validate(versionId));
    }

    /**
     * 发布草稿。
     *
     * @param versionId 版本 ID
     * @param request 请求
     * @return 发布结果
     */
    @SaCheckPermission("process:definition:publish")
    @Log(title = "计划定义", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PostMapping("/{versionId}/publish")
    public R<DefinitionValidationVo> publish(@PathVariable long versionId,
                                             @RequestBody DefinitionCommandRequest request) {
        return R.ok(planDefinitionService.publish(versionId, request.expectedVersion(), request.commandKey(),
            request.reason()));
    }

    /**
     * 复制为新草稿。
     *
     * @param versionId 版本 ID
     * @param request 请求
     * @return 新草稿
     */
    @SaCheckPermission("process:definition:copy")
    @Log(title = "计划定义", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/{versionId}/copy")
    public R<DefinitionDetailVo> copy(@PathVariable long versionId,
                                      @RequestBody DefinitionCommandRequest request) {
        return R.ok(planDefinitionService.copy(versionId, request.commandKey(), request.reason()));
    }

    /**
     * 逻辑删除最新草稿。
     *
     * @param versionId 版本 ID
     * @param expectedVersion 版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 操作结果
     */
    @SaCheckPermission("process:definition:remove")
    @Log(title = "计划定义", businessType = BusinessType.DELETE)
    @RepeatSubmit()
    @DeleteMapping("/{versionId}")
    public R<Void> delete(@PathVariable long versionId, @RequestParam long expectedVersion,
                          @RequestParam String commandKey, @RequestParam String reason) {
        planDefinitionService.delete(versionId, expectedVersion, commandKey, reason);
        return R.ok();
    }
}
