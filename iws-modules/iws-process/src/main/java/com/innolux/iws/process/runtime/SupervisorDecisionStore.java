/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : 监督决策检查点 JSON 读写
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innolux.iws.common.core.exception.ServiceException;

import java.util.List;

/**
 * 监督 DECISION 持久化的候选/选中/未选原因检查点。
 */
final class SupervisorDecisionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SupervisorDecisionStore() {
    }

    /**
     * 序列化决策检查点。
     *
     * @param decision 决策
     * @return JSON
     */
    static String toJson(Decision decision) {
        try {
            return MAPPER.writeValueAsString(decision);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("监督决策检查点序列化失败: " + ex.getMessage());
        }
    }

    /**
     * 解析决策检查点。
     *
     * @param json JSON
     * @return 决策；空值返回 null
     */
    static Decision parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Decision.class);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("监督决策检查点损坏: " + ex.getMessage());
        }
    }

    /**
     * 未选候选。
     *
     * @param instanceId 子计划 ID
     * @param reason 原因
     */
    record Skip(long instanceId, String reason) {
    }

    /**
     * 一次决策结果。
     *
     * @param roundNo 轮次
     * @param decisionCode 决策事件编码
     * @param candidateCount 候选总数
     * @param eligibleCount 可准入数
     * @param held 已占名额
     * @param limit 名额上限
     * @param free 剩余名额
     * @param fairnessWindow 公平窗口 K
     * @param fairnessCount 决策前窗口计数
     * @param fairnessTurn 是否保底窗口
     * @param selected 选中子计划
     * @param notSelected 未选原因
     * @param reason 决策原因
     */
    record Decision(long roundNo, String decisionCode, int candidateCount, int eligibleCount, int held,
                    int limit, int free, int fairnessWindow, int fairnessCount, boolean fairnessTurn,
                    List<Long> selected, List<Skip> notSelected, String reason) {

        /**
         * 空集合兜底。
         */
        Decision {
            selected = selected == null ? List.of() : List.copyOf(selected);
            notSelected = notSelected == null ? List.of() : List.copyOf(notSelected);
        }
    }
}
