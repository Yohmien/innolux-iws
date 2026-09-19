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

package com.innolux.iws.common.encrypt.core.encryptor;

import com.innolux.iws.common.encrypt.core.EncryptContext;
import com.innolux.iws.common.encrypt.core.IEncryptor;

/**
 * 所有加密执行者的基类
 *
 * @author YONGJIE.PENG
 * @version 4.6.0
 */
public abstract class AbstractEncryptor implements IEncryptor {

    /**
     * 初始化加密执行者。
     *
     * @param context 加密上下文
     */
    public AbstractEncryptor(EncryptContext context) {
        // 用户配置校验与配置注入
    }

}
