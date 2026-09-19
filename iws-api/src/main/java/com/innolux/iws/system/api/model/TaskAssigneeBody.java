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

package com.innolux.iws.system.api.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 任务受让人
 *
 * @author YONGJIE.PENG
 */
@Data
@NoArgsConstructor
public class TaskAssigneeBody implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 权限编码
     */
    private String handlerCode;

    /**
     * 权限名称
     */
    private String handlerName;

    /**
     * 权限分组
     */
    private String groupId;

    /**
     * 开始时间
     */
    private String beginTime;

    /**
     * 结束时间
     */
    private String endTime;

    /**
     * 当前页
     */
    private Integer pageNum = 1;

    /**
     * 每页显示条数
     */
    private Integer pageSize = 10;

}
