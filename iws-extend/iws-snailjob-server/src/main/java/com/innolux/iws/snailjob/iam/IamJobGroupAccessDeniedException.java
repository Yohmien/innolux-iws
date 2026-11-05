/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : Job 原生 namespace/group 授权拒绝；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

/**
 * 普通用户访问未授权 Job group 时抛出。
 */
public class IamJobGroupAccessDeniedException extends RuntimeException {

    public IamJobGroupAccessDeniedException(String message) {
        super(message);
    }
}
