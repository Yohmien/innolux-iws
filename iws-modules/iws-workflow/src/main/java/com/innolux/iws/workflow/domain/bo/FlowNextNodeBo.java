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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 查询下一节点信息的请求对象。
 *
 * @author YONGJIE.PENG
 */
@Data
public class FlowNextNodeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 任务id
     */
    private Long taskId;

    /**
     * 流程变量
     */
    private Map<String, Object> variables;

    /**
     * 获取流程变量并自动剔除空值。
     *
     * @return 流程变量
     */
    public Map<String, Object> getVariables() {
        if (variables == null) {
            variables = new HashMap<>(16);
            return variables;
        }
        variables.entrySet().removeIf(entry -> Objects.isNull(entry.getValue()));
        return variables;
    }
}
