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

package com.innolux.iws.common.social.config.properties;

import lombok.Data;

import java.util.List;

/**
 * 社交登录配置
 *
 * @author YONGJIE.PENG
 */
@Data
public class SocialLoginConfigProperties {

    /**
     * 应用 ID
     */
    private String clientId;

    /**
     * 应用密钥
     */
    private String clientSecret;

    /**
     * 回调地址
     */
    private String redirectUri;

    /**
     * 是否需要申请unionid，目前只针对qq登录
     */
    private Boolean unionId;

    /**
     * Microsoft Entra ID（原微软 AAD）中的租户 ID
     */
    private String tenantId;

    /**
     * Coding 企业名称
     */
    private String codingGroupName;

    /**
     * 支付宝公钥
     */
    private String alipayPublicKey;

    /**
     * 企业微信应用ID
     */
    private String agentId;

    /**
     * stackoverflow api key
     */
    private String stackOverflowKey;

    /**
     * 设备ID
     */
    private String deviceId;

    /**
     * 客户端系统类型
     */
    private String clientOsType;

    /**
     * 自托管授权服务器地址
     */
    private String serverUrl;

    /**
     * 请求范围
     */
    private List<String> scopes;

}
