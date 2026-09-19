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

package com.innolux.iws.demo.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import com.innolux.iws.demo.domain.TestDemo;

/**
 * 测试单表业务对象 test_demo
 *
 * @author YONGJIE.PENG
 * @date 2021-07-26
 */
@Data
@AutoMapper(target = TestDemo.class)
public class TestDemoImportVo {

    /**
     * 部门id
     */
    @NotNull(message = "部门id不能为空")
    @ExcelProperty(value = "部门id")
    private Long deptId;

    /**
     * 用户id
     */
    @NotNull(message = "用户id不能为空")
    @ExcelProperty(value = "用户id")
    private Long userId;

    /**
     * 排序号
     */
    @NotNull(message = "排序号不能为空")
    @ExcelProperty(value = "排序号")
    private Long orderNum;

    /**
     * key键
     */
    @NotBlank(message = "key键不能为空")
    @ExcelProperty(value = "key键")
    private String testKey;

    /**
     * 值
     */
    @NotBlank(message = "值不能为空")
    @ExcelProperty(value = "值")
    private String value;

}
