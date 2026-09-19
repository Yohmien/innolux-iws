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

package com.innolux.iws.demo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.enums.PushSourceEnum;
import com.innolux.iws.common.core.enums.PushTypeEnum;
import com.innolux.iws.system.api.MessageService;
import com.innolux.iws.system.api.domain.PushPayloadDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WebSocket 演示案例
 *
 * @author YONGJIE.PENG
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/demo/websocket")
@Slf4j
public class WebSocketController {

    private final MessageService messageService;

    /**
     * 发布消息
     *
     * @param userId  目标用户
     * @param message 发送内容
     */
    @GetMapping("/send")
    public R<Void> send(Long userId, String message) {
        PushPayloadDTO payload = PushPayloadDTO.of(
            PushTypeEnum.MESSAGE,
            PushSourceEnum.BACKEND,
            message,
            null
        );
        if (userId == null) {
            messageService.publishAll(payload);
        } else {
            messageService.publishMessage(List.of(userId), payload);
        }
        return R.ok("操作成功");
    }
}
