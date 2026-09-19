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

package com.innolux.iws.workflow.rule;

import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.api.DeptService;
import com.innolux.iws.workflow.common.ConditionalOnEnable;
import org.springframework.stereotype.Component;

/**
 * spel表达式规则组件
 * <p>
 * 通过该组件统一管理流程定义中的spel表达式
 * </p>
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnEnable
@Slf4j
@Component
@RequiredArgsConstructor
public class SpelRuleComponent {

    private final DeptService deptService;

    /**
     * 通过发起人部门 ID 获取部门负责人。
     *
     * @param initiatorDeptId 发起人部门 ID
     * @return 部门负责人用户 ID
     */
    public Long selectDeptLeaderById(Long initiatorDeptId) {
        Long leaderId = deptService.selectDeptLeaderById(initiatorDeptId);
        if (ObjectUtil.isNull(leaderId)) {
            throw new ServiceException("当前部门未设置负责人，请联系管理员操作。");
        }
        return leaderId;
    }

}
