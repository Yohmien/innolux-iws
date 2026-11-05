/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 为统一身份中心客户端提供有界连接和响应时间；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.common.auth.utils;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 中心协议 HTTP 客户端工厂。
 */
public final class IamHttpClientFactory {

    private IamHttpClientFactory() {
    }

    /**
     * 创建带有限连接和响应截止时间的 RestClient。
     *
     * @param baseUrl 中心基址
     * @param connectTimeoutMs 连接超时毫秒
     * @param responseTimeoutMs 响应超时毫秒
     * @return RestClient 构造器
     */
    public static RestClient.Builder builder(
        String baseUrl,
        long connectTimeoutMs,
        long responseTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory =
            new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(toTimeout(connectTimeoutMs));
        requestFactory.setReadTimeout(toTimeout(responseTimeoutMs));
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory);
    }

    private static int toTimeout(long milliseconds) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, milliseconds));
    }
}
