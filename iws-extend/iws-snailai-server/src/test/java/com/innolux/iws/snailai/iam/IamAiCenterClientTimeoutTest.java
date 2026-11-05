/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : AI 统一身份接入
 * @Version       : V1.0
 * @Description  : 验证中心接受连接但不返回时，状态检查使用有限响应截止；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailai.iam;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class IamAiCenterClientTimeoutTest {

    @Test
    void acceptedConnectionWithDelayedResponseCannotBlockForever() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean requestReceived = new AtomicBoolean();
        server.createContext("/iam/session/status", exchange -> {
            requestReceived.set(true);
            try {
                Thread.sleep(2_000L);
                byte[] body = "{\"code\":200,\"data\":null}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            IamAiProperties properties = new IamAiProperties();
            properties.setCenterBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setPublicBaseUrl("http://127.0.0.1");
            properties.setApplicationId("snail-ai");
            properties.setApplicationType("SNAIL_AI");
            properties.setFrontendId("ai-main");
            properties.setInstanceId("snail-ai-test");
            properties.setClientSecret("isolated-test-secret");
            properties.setConnectTimeoutMs(200);
            properties.setResponseTimeoutMs(400);
            IamAiCenterClient client = new IamAiCenterClient(properties);

            long startedAt = System.nanoTime();
            assertThrows(
                IllegalStateException.class,
                () -> client.status("root", "ai-main", "session", 1L, "WEB")
            );
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            assertTrue(requestReceived.get(),
                "测试服务应已经接受请求，验证的是响应阶段截止而非端口拒绝");
            assertTrue(elapsedMs < 1_500L, "中心响应截止时间未生效，实际耗时 " + elapsedMs + "ms");
        } finally {
            server.stop(0);
        }
    }
}
