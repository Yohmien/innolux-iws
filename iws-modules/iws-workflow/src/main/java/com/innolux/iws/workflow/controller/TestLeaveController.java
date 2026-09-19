/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.common.excel.utils.ExcelBuilder;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import com.innolux.iws.workflow.domain.bo.TestLeaveBo;
import com.innolux.iws.workflow.domain.vo.TestLeaveVo;
import com.innolux.iws.workflow.service.ITestLeaveService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 请假示例控制器，演示业务单据与流程引擎联动的典型用法。
 *
 * @author YONGJIE.PENG
 * @date 2023-07-21
 */
@ConditionalOnEnable
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/workflow/leave")
public class TestLeaveController extends BaseController {

    private final ITestLeaveService testLeaveService;

    /**
     * 分页查询请假列表。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 请假分页数据
     */
    @SaCheckPermission("workflow:leave:list")
    @GetMapping("/list")
    public R<PageResult<TestLeaveVo>> list(TestLeaveBo bo, PageQuery pageQuery) {
        return R.ok(testLeaveService.queryPageList(bo, pageQuery));
    }

    /**
     * 导出请假列表。
     *
     * @param bo       查询条件
     * @param response 响应流
     */
    @SaCheckPermission("workflow:leave:export")
    @Log(title = "请假", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(TestLeaveBo bo, HttpServletResponse response) {
        List<TestLeaveVo> list = testLeaveService.queryList(bo);
        ExcelBuilder.of(list, TestLeaveVo.class).sheetName("请假").toResponse(response);
    }

    /**
     * 获取请假单详情。
     *
     * @param id 主键
     * @return 请假详情
     */
    @SaCheckPermission("workflow:leave:query")
    @GetMapping("/{id}")
    public R<TestLeaveVo> getInfo(@NotNull(message = "主键不能为空")
                                  @PathVariable Long id) {
        return R.ok(testLeaveService.queryById(id));
    }

    /**
     * 新增请假单。
     *
     * @param bo 请假信息
     * @return 新增后的请假单
     */
    @SaCheckPermission("workflow:leave:add")
    @Log(title = "请假", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping()
    public R<TestLeaveVo> add(@Validated(AddGroup.class) @RequestBody TestLeaveBo bo) {
        return R.ok(testLeaveService.insertByBo(bo));
    }

    /**
     * 提交请假单并同步发起流程。
     *
     * @param bo 请假信息
     * @return 提交后的请假单
     */
    @SaCheckPermission("workflow:leave:add")
    @Log(title = "请假", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping("/submitAndFlowStart")
    public R<TestLeaveVo> submitAndFlowStart(@Validated(AddGroup.class) @RequestBody TestLeaveBo bo) {
        return R.ok(testLeaveService.submitAndFlowStart(bo));
    }

    /**
     * 修改请假单。
     *
     * @param bo 请假信息
     * @return 修改后的请假单
     */
    @SaCheckPermission("workflow:leave:edit")
    @Log(title = "请假", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping()
    public R<TestLeaveVo> edit(@Validated(EditGroup.class) @RequestBody TestLeaveBo bo) {
        return R.ok(testLeaveService.updateByBo(bo));
    }

    /**
     * 批量删除请假单。
     *
     * @param ids 主键串
     * @return 操作结果
     */
    @SaCheckPermission("workflow:leave:remove")
    @Log(title = "请假", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(testLeaveService.deleteWithValidByIds(List.of(ids)));
    }
}
