/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 计划内系统模式、屏蔽与跳过合同的纯规则；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.support;

import com.innolux.iws.process.domain.PlanDefinition;
import com.innolux.iws.process.domain.ProcessEdge;
import com.innolux.iws.process.domain.ProcessEventDefinition;
import com.innolux.iws.process.enums.CallPurpose;
import com.innolux.iws.process.enums.EventDirection;
import com.innolux.iws.process.enums.OperationKind;
import com.innolux.iws.process.enums.PostingMode;
import com.innolux.iws.process.enums.SkipPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统模式与屏蔽合同的纯函数规则，供发布校验、影响预览和聚合保存共用。
 *
 * <p>只解释定义对象，不访问目录、数据库或业务动作。</p>
 */
public final class DefinitionPostingRules {

    private DefinitionPostingRules() {
    }

    /**
     * 判断事件是否属于可被系统模式屏蔽的唯一外部出站抛账。
     *
     * @param definition 计划定义
     * @param event 事件
     * @return true 表示命中屏蔽规则
     */
    public static boolean bypassableOutbound(PlanDefinition definition, ProcessEventDefinition event) {
        return event.getEventDirection() == EventDirection.OUTBOUND
            && event.getOperationKind() == OperationKind.EXTERNAL_POSTING
            && event.getTargetSystemCode() != null
            && event.getTargetSystemCode().equals(event.getSystemCode())
            && !event.getTargetSystemCode().equals(definition.getSelfSystemCode());
    }

    /**
     * 判断事件是否在指定模式向量下零发送。
     *
     * @param definition 计划定义
     * @param event 事件
     * @param modes 系统模式
     * @return true 表示本次不发送
     */
    public static boolean skippedByMode(PlanDefinition definition, ProcessEventDefinition event,
                                        Map<String, PostingMode> modes) {
        return bypassableOutbound(definition, event)
            && modes.get(event.getSystemCode()) == PostingMode.BYPASS;
    }

    /**
     * 完整政策系统集合：全部事件声明的 systemCode，含禁用、候选分支和恢复查询事件。
     *
     * @param events 事件
     * @return 稳定排序的系统编码
     */
    public static List<String> policySystems(List<ProcessEventDefinition> events) {
        Set<String> codes = new LinkedHashSet<>();
        for (ProcessEventDefinition event : events) {
            if (event.getSystemCode() != null && !event.getSystemCode().isBlank()) {
                codes.add(event.getSystemCode());
            }
        }
        List<String> result = new ArrayList<>(codes);
        result.sort(String::compareTo);
        return result;
    }

    /**
     * 汇总可屏蔽、不受影响和整事件禁用的事件分组。
     *
     * @param definition 计划定义
     * @param events 事件
     * @param systemCode 系统编码
     * @return 分组结果
     */
    public static EventGroups groupEvents(PlanDefinition definition, List<ProcessEventDefinition> events,
                                          String systemCode) {
        List<String> bypassable = new ArrayList<>();
        List<String> unaffected = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (ProcessEventDefinition event : events) {
            if (!systemCode.equals(event.getSystemCode())) {
                continue;
            }
            if (Boolean.FALSE.equals(event.getEnabled())) {
                disabled.add(event.getEventCode());
            } else if (bypassableOutbound(definition, event)) {
                bypassable.add(event.getEventCode());
            } else {
                unaffected.add(event.getEventCode());
            }
        }
        return new EventGroups(bypassable, unaffected, disabled);
    }

    /**
     * 校验所有 BYPASS 外部出站的跳过合同。
     *
     * <p>ACCEPTANCE 出站必须有下游结果回调事件，且该结果事件显式允许跳过并登记跳过合同；
     * 否则屏蔽会伪造外部完成。仅有入站/查询/控制的系统不允许配置 BYPASS。</p>
     *
     * @param definition 计划定义
     * @param events 事件
     * @param edges 边
     * @param modes 系统模式
     * @return 错误列表
     */
    public static List<String> validateBypassContracts(PlanDefinition definition,
                                                       List<ProcessEventDefinition> events,
                                                       List<ProcessEdge> edges,
                                                       Map<String, PostingMode> modes) {
        List<String> errors = new ArrayList<>();
        Map<String, ProcessEventDefinition> byCode = new LinkedHashMap<>();
        for (ProcessEventDefinition event : events) {
            byCode.put(event.getEventCode(), event);
        }
        Map<String, List<String>> successors = successors(edges);
        for (Map.Entry<String, PostingMode> entry : new LinkedHashMap<>(modes).entrySet()) {
            if (entry.getValue() != PostingMode.BYPASS) {
                continue;
            }
            boolean hasBypassable = events.stream()
                .anyMatch(event -> entry.getKey().equals(event.getSystemCode())
                    && bypassableOutbound(definition, event));
            if (!hasBypassable) {
                errors.add("系统[" + entry.getKey() + "]没有可屏蔽的外部出站抛账事件，不能配置 BYPASS");
            }
        }
        for (ProcessEventDefinition event : events) {
            if (!bypassableOutbound(definition, event)
                || modes.get(event.getSystemCode()) != PostingMode.BYPASS) {
                continue;
            }
            if (event.getCompletionContract() != null
                && event.getCompletionContract().name().equals("ACCEPTANCE")
                && !hasResultSkipAlternative(event.getEventCode(), byCode, successors)) {
                errors.add("BYPASS 的 ACCEPTANCE 出站缺少显式结果事件跳过替代: " + event.getEventCode());
            }
        }
        return errors;
    }

    /**
     * 判断下游是否存在显式允许跳过的结果回调事件。
     *
     * @param origin 出站事件编码
     * @param events 事件索引
     * @param successors 后继索引
     * @return true 表示存在替代合同
     */
    public static boolean hasResultSkipAlternative(String origin, Map<String, ProcessEventDefinition> events,
                                                   Map<String, List<String>> successors) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(successors.getOrDefault(origin, List.of()));
        while (!queue.isEmpty()) {
            String code = queue.removeFirst();
            if (!visited.add(code)) {
                continue;
            }
            ProcessEventDefinition event = events.get(code);
            if (event != null && event.getEventDirection() == EventDirection.OUTBOUND) {
                // 只接受本出站分支自己的结果事件，不能穿过下一个业务出站借用别人的回调替代。
                continue;
            }
            if (event != null && event.getEventDirection() == EventDirection.INBOUND
                && event.getPurpose() == CallPurpose.RESULT_CALLBACK
                && event.getSkipPolicy() == SkipPolicy.ALLOW
                && event.getSkipContractKey() != null
                && !event.getSkipContractKey().isBlank()) {
                return true;
            }
            queue.addAll(successors.getOrDefault(code, List.of()));
        }
        return false;
    }

    /**
     * 构建后继索引。
     *
     * @param edges 边
     * @return 后继索引
     */
    public static Map<String, List<String>> successors(List<ProcessEdge> edges) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (ProcessEdge edge : edges) {
            result.computeIfAbsent(edge.getPredecessorEventCode(), key -> new ArrayList<>())
                .add(edge.getSuccessorEventCode());
        }
        return result;
    }

    /**
     * 单系统的三组事件。
     *
     * @param bypassable 可屏蔽事件
     * @param unaffected 不受影响事件
     * @param disabled 已禁用事件
     */
    public record EventGroups(List<String> bypassable, List<String> unaffected, List<String> disabled) {
    }
}
