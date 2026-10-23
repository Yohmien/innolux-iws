/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : OUTBOUND CALL 资源容量原子预占；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.process.runtime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.innolux.iws.process.config.ProcessProperties;
import com.innolux.iws.process.domain.ProcessScheduleControl;
import com.innolux.iws.process.enums.PermitState;
import com.innolux.iws.process.enums.ScheduleControlScope;
import com.innolux.iws.process.mapper.ProcessScheduleControlMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * CALL 容量控制。容量控制行使用 FOR UPDATE 串行 count→insert。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "iws.process", name = "enabled", havingValue = "true")
public class CallCapacityService {

    private final ProcessScheduleControlMapper controlMapper;

    private final ProcessProperties properties;

    /**
     * 构造容量服务。
     *
     * @param controlMapper 控制 Mapper
     * @param properties process 配置
     */
    public CallCapacityService(ProcessScheduleControlMapper controlMapper, ProcessProperties properties) {
        this.controlMapper = controlMapper;
        this.properties = properties;
    }

    /**
     * 在 T2 事务内检查容量；不创建 permit。
     *
     * @param resourceKey 资源键
     * @param eventId 原 OUTBOUND 事件 ID
     * @return 是否允许创建 SEND_INTENT
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAcquire(String resourceKey, long eventId) {
        ProcessScheduleControl existing = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, String.valueOf(eventId))
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT"));
        if (existing != null && existing.getPermitState() != PermitState.RELEASED) {
            return true;
        }
        Integer configured = properties.getCallCapacities().get(resourceKey);
        if (configured == null || configured <= 0) {
            return false;
        }
        String capacityKey = "CAPACITY:" + resourceKey;
        ProcessScheduleControl capacity = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getScopeKey, capacityKey)
            .eq(ProcessScheduleControl::getDuty, "CALL_CAPACITY")
            .last("FOR UPDATE"));
        if (capacity == null) {
            capacity = new ProcessScheduleControl();
            capacity.setScopeType(ScheduleControlScope.CALL);
            capacity.setScopeKey(capacityKey);
            capacity.setDuty("CALL_CAPACITY");
            capacity.setRevision(1L);
            capacity.setControlState(com.innolux.iws.process.enums.ControlState.AUTO_RUNNING);
            capacity.setDesiredState("ACTIVE");
            capacity.setObservedState("ACTIVE");
            capacity.setResourceKey(resourceKey);
            capacity.setWakePending(Boolean.FALSE);
            capacity.setLastActivityAt(LocalDateTime.now());
            try {
                controlMapper.insert(capacity);
            } catch (DuplicateKeyException ex) {
                capacity = controlMapper.selectOne(Wrappers.<ProcessScheduleControl>lambdaQuery()
                    .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
                    .eq(ProcessScheduleControl::getScopeKey, capacityKey)
                    .eq(ProcessScheduleControl::getDuty, "CALL_CAPACITY")
                    .last("FOR UPDATE"));
            }
        }
        long active = controlMapper.selectCount(Wrappers.<ProcessScheduleControl>lambdaQuery()
            .eq(ProcessScheduleControl::getScopeType, ScheduleControlScope.CALL)
            .eq(ProcessScheduleControl::getResourceKey, resourceKey)
            .eq(ProcessScheduleControl::getDuty, "CALL_PERMIT")
            .ne(ProcessScheduleControl::getScopeKey, String.valueOf(eventId))
            .in(ProcessScheduleControl::getPermitState, PermitState.INTENT, PermitState.IN_FLIGHT,
                PermitState.ACCEPTED, PermitState.WAIT_RESULT)
            .last("FOR UPDATE"));
        return active < configured;
    }
}
