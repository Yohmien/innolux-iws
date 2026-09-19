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

package com.innolux.iws.workflow.domain.bo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.innolux.iws.common.core.validate.AddGroup;
import com.innolux.iws.common.core.validate.EditGroup;
import com.innolux.iws.workflow.domain.TestLeave;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 请假业务对象 test_leave
 *
 * @author YONGJIE.PENG
 * @date 2023-07-21
 */
@Data
@AutoMapper(target = TestLeave.class, reverseConvertGenerate = false)
public class TestLeaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 流程code
     */
    private String flowCode;

    /**
     * 申请编号
     */
    private String applyCode;

    /**
     * 请假类型
     */
    @NotBlank(message = "请假类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String leaveType;

    /**
     * 开始时间
     */
    @NotNull(message = "开始时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDateTime startDate;

    /**
     * 结束时间
     */
    @NotNull(message = "结束时间不能为空", groups = {AddGroup.class, EditGroup.class})
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDateTime endDate;

    /**
     * 请假天数
     */
    private Integer leaveDays;

    /**
     * 开始时间
     */
    private Integer startLeaveDays;

    /**
     * 结束时间
     */
    private Integer endLeaveDays;

    /**
     * 请假原因
     */
    private String remark;

    /**
     * 状态
     */
    private String status;

    /**
     * 请求参数
     */
    private Map<String, Object> params = new HashMap<>();

}
