/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 测试专用壳层响应；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import com.innolux.iws.common.core.domain.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 仅用于 fixture 浏览器壳层的最小响应。
 */
@RestController
@RequestMapping
public class ProcessHttpFixtureShellController {

    /**
     * 空消息箱。
     *
     * @return 空消息集合
     */
    @GetMapping("/resource/message/box")
    public R<Map<String, List<?>>> messageBox() {
        return R.ok(Map.of(
            "systemList", List.of(),
            "noticeList", List.of(),
            "workflowList", List.of()
        ));
    }

    /**
     * 保持一个无事件 SSE 连接。
     *
     * @return 不超时的 SSE 连接
     */
    @GetMapping(value = "/resource/message", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter messageStream() {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().comment("fixture-ready"));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /**
     * 关闭消息连接。
     *
     * @return 操作结果
     */
    @PostMapping("/resource/message/close")
    public R<Void> closeMessage() {
        return R.ok();
    }

    /**
     * 提示自动关闭时长。
     *
     * @return 默认 30 秒
     */
    @GetMapping("/system/config/configKey/sys.ui.messageCloseSeconds")
    public R<Integer> messageCloseSeconds() {
        return R.ok(30);
    }
}
