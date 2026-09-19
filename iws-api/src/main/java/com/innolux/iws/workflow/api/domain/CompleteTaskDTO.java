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

package com.innolux.iws.workflow.api.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 办理任务请求对象
 *
 * @author YONGJIE.PENG
 */
@Data
public class CompleteTaskDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务id
     */
    private Long taskId;

    /**
     * 附件id
     */
    private String fileId;

    /**
     * 抄送人员
     */
    private List<FlowCopyDTO> flowCopyList;

    /**
     * 消息类型
     */
    private List<String> messageType;

    /**
     * 办理意见
     */
    private String message;

    /**
     * 消息通知
     */
    private String notice;

    /**
     * 办理人(可不填 用于覆盖当前节点办理人)
     */
    private String handler;

    /**
     * 流程变量
     */
    private Map<String, Object> variables;

    /**
     * 扩展变量(此处为逗号分隔的ossId)
     */
    private String ext;

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
