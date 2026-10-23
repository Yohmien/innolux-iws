/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 业务统一调度配置入口；
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
import com.innolux.iws.process.api.BusinessScheduleVo;
import com.innolux.iws.process.api.PlanTypeSaveRequest;
import com.innolux.iws.process.api.PlanTypeVo;
import com.innolux.iws.process.api.ScheduleSaveRequest;
import com.innolux.iws.process.service.BusinessScheduleService;
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
 * 业务调度配置入口。
 */
@Validated
@RestController
@RequestMapping("/process/schedule")
@ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class BusinessScheduleController {

    private final BusinessScheduleService businessScheduleService;

    /**
     * 构造控制器。
     *
     * @param businessScheduleService 业务调度服务
     */
    public BusinessScheduleController(BusinessScheduleService businessScheduleService) {
        this.businessScheduleService = businessScheduleService;
    }

    /**
     * 分页查询。
     *
     * @param businessCode 业务编码
     * @param enabled 是否启用
     * @param pageQuery 分页
     * @return 分页结果
     */
    @SaCheckPermission("process:schedule:list")
    @GetMapping("/page")
    public R<PageResult<BusinessScheduleVo>> page(@RequestParam(required = false) String businessCode,
                                                  @RequestParam(required = false) Boolean enabled,
                                                  PageQuery pageQuery) {
        return R.ok(businessScheduleService.page(businessCode, enabled, pageQuery));
    }

    /**
     * 筛选选项。
     *
     * @return 已启用业务调度
     */
    @SaCheckPermission("process:schedule:list")
    @GetMapping("/options")
    public R<List<BusinessScheduleVo>> options() {
        PageResult<BusinessScheduleVo> page = businessScheduleService.page(null, Boolean.TRUE, new PageQuery());
        return R.ok(List.copyOf(page.getRows()));
    }

    /**
     * 查询详情。
     *
     * @param id 业务调度 ID
     * @return 详情
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/{id}")
    public R<BusinessScheduleVo> get(@PathVariable long id) {
        return R.ok(businessScheduleService.get(id));
    }

    /**
     * 新增业务调度。
     *
     * @param request 保存请求
     * @return 详情
     */
    @SaCheckPermission("process:schedule:add")
    @Log(title = "计划业务调度", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<BusinessScheduleVo> create(@RequestBody ScheduleSaveRequest request) {
        return R.ok(businessScheduleService.create(request.schedule(), request.commandKey(), request.reason()));
    }

    /**
     * 修改业务调度。
     *
     * @param id 业务调度 ID
     * @param request 保存请求
     * @return 详情
     */
    @SaCheckPermission("process:schedule:edit")
    @Log(title = "计划业务调度", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{id}")
    public R<BusinessScheduleVo> update(@PathVariable long id, @RequestBody ScheduleSaveRequest request) {
        if (request.expectedRevision() == null) {
            return R.fail("expectedRevision 不能为空");
        }
        return R.ok(businessScheduleService.update(id, request.schedule(), request.expectedRevision(),
            request.commandKey(), request.reason()));
    }

    /**
     * 删除业务调度。
     *
     * @param id 业务调度 ID
     * @param expectedRevision 版本
     * @param commandKey 命令键
     * @param reason 原因
     * @return 操作结果
     */
    @SaCheckPermission("process:schedule:remove")
    @Log(title = "计划业务调度", businessType = BusinessType.DELETE)
    @RepeatSubmit()
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id, @RequestParam long expectedRevision,
                          @RequestParam String commandKey, @RequestParam String reason) {
        businessScheduleService.delete(id, expectedRevision, commandKey, reason);
        return R.ok();
    }

    /**
     * 查询计划类型。
     *
     * @param id 业务调度 ID
     * @return 计划类型
     */
    @SaCheckPermission("process:schedule:query")
    @GetMapping("/{id}/plan-types")
    public R<List<PlanTypeVo>> planTypes(@PathVariable long id) {
        return R.ok(businessScheduleService.planTypes(id));
    }

    /**
     * 原子保存计划类型集合。
     *
     * @param id 业务调度 ID
     * @param request 保存请求
     * @return 最新集合
     */
    @SaCheckPermission("process:schedule:edit")
    @Log(title = "计划类型", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/{id}/plan-types")
    public R<List<PlanTypeVo>> savePlanTypes(@PathVariable long id, @RequestBody PlanTypeSaveRequest request) {
        return R.ok(businessScheduleService.savePlanTypes(id, request.planTypes(), request.expectedRevision(),
            request.commandKey(), request.reason()));
    }
}
