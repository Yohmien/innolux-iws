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

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 流程实例查询条件对象。
 *
 * @author YONGJIE.PENG
 */
@Data
public class FlowInstanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 流程定义名称
     */
    private String flowName;

    /**
     * 流程定义编码
     */
    private String flowCode;

    /**
     * 任务发起人
     */
    private String startUserId;

    /**
     * 业务id
     */
    private String businessId;

    /**
     * 流程分类id
     */
    private String category;

    /**
     * 任务名称
     */
    private String nodeName;

    /**
     * 申请人Ids
     */
    private List<String> createByIds;

}
