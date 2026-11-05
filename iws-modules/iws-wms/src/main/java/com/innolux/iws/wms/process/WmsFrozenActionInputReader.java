/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理计划动作
 * @Version      : V1.0
 * @Description  : 从已持久业务任务读取 LocalAction 完整冻结输入；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.process;

import com.innolux.iws.common.auth.identity.IamIdentityFacts;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.process.runtime.ActionContext;
import com.innolux.iws.process.runtime.LocalActionInput;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 业务任务冻结输入读取器。 */
public class WmsFrozenActionInputReader {

    private final JdbcTemplate jdbcTemplate;

    private final IamIdentityFacts identityFacts;

    public WmsFrozenActionInputReader(JdbcTemplate jdbcTemplate, IamIdentityFacts identityFacts) {
        this.jdbcTemplate = jdbcTemplate;
        this.identityFacts = identityFacts;
    }

    /**
     * 按计划实例读取唯一业务任务冻结输入。
     *
     * @param context 平台上下文
     * @param actionKey 本地动作键
     * @return 冻结输入
     */
    public LocalActionInput read(ActionContext context, String actionKey) {
        if (context == null || context.instanceId() <= 0) {
            throw new ServiceException("业务任务冻结输入缺少计划实例");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select task_id, input_hash, input_json
            from wms_business_task
            where plan_instance_id = ?
            """, context.instanceId());
        if (rows.size() != 1) {
            throw new ServiceException("计划实例必须绑定唯一业务任务冻结输入: "
                + context.instanceId());
        }
        Map<String, Object> row = rows.get(0);
        String inputHash = Objects.toString(row.get("input_hash"), "");
        if (!inputHash.equals(context.inputHash())) {
            throw new ServiceException("业务任务冻结输入摘要与平台事件不一致");
        }
        String inputJson = Objects.toString(row.get("input_json"), "");
        if (inputJson.isBlank()) {
            throw new ServiceException("业务任务冻结输入为空");
        }
        return new LocalActionInput(
            inputJson,
            "wms-business-task:" + row.get("task_id"),
            Map.of(
                "businessTaskId", String.valueOf(row.get("task_id")),
                "localActionKey", actionKey
            )
        );
    }

    /**
     * 读取业务任务冻结的发起人身份，供无 HTTP 会话的后台动作复核授权。
     *
     * @param context 平台上下文
     * @return 持久发起人身份
     */
    public WmsExecutionActor actor(ActionContext context) {
        if (context == null || context.instanceId() <= 0) {
            throw new ServiceException("后台动作缺少计划实例，无法解析发起人");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select task.create_by, task.create_dept
              from wms_business_task task
             where task.plan_instance_id = ?
            """, context.instanceId());
        if (rows.size() != 1) {
            throw new ServiceException("计划实例必须绑定唯一业务任务发起人: "
                + context.instanceId());
        }
        Map<String, Object> row = rows.getFirst();
        Long userId = nullableLong(row.get("create_by"));
        if (userId == null || userId <= 0) {
            throw new ServiceException("业务任务缺少持久发起人身份");
        }
        Long deptId = nullableLong(row.get("create_dept"));
        IamIdentityFacts.UserFact user = identityFacts.findUser(userId);
        String userName = user == null
            ? "USER:" + userId
            : text(user.userName(), "USER:" + userId);
        String nickName = user == null ? userName : text(user.nickName(), userName);
        IamIdentityFacts.DeptFact dept = identityFacts.findDept(deptId);
        return new WmsExecutionActor(
            userId,
            userName,
            nickName,
            deptId,
            dept == null ? null : text(dept.deptName(), null)
        );
    }

    private static Long nullableLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String text(Object value, String defaultValue) {
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? defaultValue : text;
    }
}
