/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : 端点目录版本冲突；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.catalog;

import java.io.Serial;

/**
 * 端点目录版本冲突。
 *
 * <p>消费方应回滚当前事务，重新读取目录与定义后让用户重新预览。</p>
 */
public class EndpointCatalogConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String expectedRevision;
    private final String actualRevision;

    /**
     * 构造目录版本冲突。
     *
     * @param expectedRevision 消费方提交的版本
     * @param actualRevision 当前目录版本
     */
    public EndpointCatalogConflictException(String expectedRevision, String actualRevision) {
        super("端点目录已变化，expectedRevision=" + expectedRevision + "，actualRevision=" + actualRevision);
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    /**
     * 获取消费方提交版本。
     *
     * @return 期望版本
     */
    public String getExpectedRevision() {
        return expectedRevision;
    }

    /**
     * 获取当前版本。
     *
     * @return 当前目录版本
     */
    public String getActualRevision() {
        return actualRevision;
    }
}
