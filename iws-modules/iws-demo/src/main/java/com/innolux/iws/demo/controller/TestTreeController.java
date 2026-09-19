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

package com.innolux.iws.demo.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.common.core.validate.QueryGroup;
import com.innolux.iws.common.excel.utils.ExcelBuilder;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.demo.domain.bo.TestTreeBo;
import com.innolux.iws.demo.domain.vo.TestTreeVo;
import com.innolux.iws.demo.service.ITestTreeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * 测试树表Controller
 *
 * @author YONGJIE.PENG
 * @date 2021-07-26
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/demo/tree")
public class TestTreeController extends BaseController {

    private final ITestTreeService testTreeService;

    /**
     * 查询测试树表列表
     */
    @SaCheckPermission("demo:tree:list")
    @GetMapping("/list")
    public R<List<TestTreeVo>> list(@Validated(QueryGroup.class) TestTreeBo bo) {
        List<TestTreeVo> list = testTreeService.queryList(bo);
        return R.ok(list);
    }

    /**
     * 导出测试树表列表
     */
    @SaCheckPermission("demo:tree:export")
    @Log(title = "测试树表", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(@Validated TestTreeBo bo, HttpServletResponse response) {
        List<TestTreeVo> list = testTreeService.queryList(bo);
        ExcelBuilder.of(list, TestTreeVo.class).sheetName("测试树表").toResponse(response);
    }

    /**
     * 获取测试树表详细信息
     *
     * @param id 测试树ID
     */
    @SaCheckPermission("demo:tree:query")
    @GetMapping("/{id}")
    public R<TestTreeVo> getInfo(@NotNull(message = "主键不能为空")
                                 @PathVariable("id") Long id) {
        return R.ok(testTreeService.queryById(id));
    }

    /**
     * 新增测试树表
     */
    @SaCheckPermission("demo:tree:add")
    @Log(title = "测试树表", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping()
    public R<Void> add(@Validated(AddGroup.class) @RequestBody TestTreeBo bo) {
        return toAjax(testTreeService.insertByBo(bo));
    }

    /**
     * 修改测试树表
     */
    @SaCheckPermission("demo:tree:edit")
    @Log(title = "测试树表", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping()
    public R<Void> edit(@Validated(EditGroup.class) @RequestBody TestTreeBo bo) {
        return toAjax(testTreeService.updateByBo(bo));
    }

    /**
     * 删除测试树表
     *
     * @param ids 测试树ID串
     */
    @SaCheckPermission("demo:tree:remove")
    @Log(title = "测试树表", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空")
                          @PathVariable Long[] ids) {
        return toAjax(testTreeService.deleteWithValidByIds(Arrays.asList(ids), true));
    }
}
