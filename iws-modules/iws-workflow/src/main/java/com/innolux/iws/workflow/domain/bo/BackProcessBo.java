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

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import com.innolux.iws.common.core.validate.AddGroup;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * 驳回参数请求
 *
 * @author YONGJIE.PENG
 */
@Data
public class BackProcessBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     */
    @NotNull(message = "任务ID不能为空", groups = AddGroup.class)
    private Long taskId;

    /**
     * 附件id
     */
    private String fileId;

    /**
     * 消息类型
     */
    private List<String> messageType;

    /**
     * 驳回的节点id(目前未使用，直接驳回到申请人)
     */
    private String nodeCode;

    /**
     * 办理意见
     */
    private String message;

    /**
     * 通知
     */
    private String notice;

    /**
     * 流程变量
     */
    private Map<String, Object> variables;

    public Map<String, Object> getVariables() {
        if (variables == null) {
            variables = new HashMap<>(16);
            return variables;
        }
        variables.entrySet().removeIf(entry -> Objects.isNull(entry.getValue()));
        return variables;
    }
}
