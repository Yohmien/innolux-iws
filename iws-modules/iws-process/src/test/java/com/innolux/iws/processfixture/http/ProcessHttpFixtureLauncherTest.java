/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 后台宿主启动入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 仅用于人工浏览器联调的后台 fixture 启动器。
 */
@Tag("dev")
@EnabledIf("fixtureHoldEnabled")
class ProcessHttpFixtureLauncherTest {

    /**
     * 需要显式开启 HTTP hold 开关才启动。
     *
     * @return true 表示保持测试宿主后台运行
     */
    static boolean fixtureHoldEnabled() {
        return Boolean.parseBoolean(System.getProperty("iws.process.test.enabled", "false"))
            && Boolean.parseBoolean(System.getProperty("iws.process.http.hold", "false"));
    }

    /**
     * 启动随机端口宿主并等待人工终止。
     *
     * @throws InterruptedException 进程被终止
     */
    @Test
    void holdFixtureServer() throws InterruptedException {
        ProcessHttpFixtureApplication.main(new String[]{
            "--server.address=127.0.0.1",
            "--server.port=0"
        });
    }
}
