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
import com.innolux.iws.common.encrypt.enums.AlgorithmType;
import com.innolux.iws.common.encrypt.enums.EncodeType;
import com.innolux.iws.common.encrypt.utils.EncryptUtils;

/**
 * Base64算法实现
 *
 * @author YONGJIE.PENG
 * @version 4.6.0
 */
public class Base64Encryptor extends AbstractEncryptor {

    /**
     * 初始化 Base64 加密执行者。
     *
     * @param context 加密上下文
     */
    public Base64Encryptor(EncryptContext context) {
        super(context);
    }

    /**
     * 获得当前算法
     */
    @Override
    public AlgorithmType algorithm() {
        return AlgorithmType.BASE64;
    }

    /**
     * 加密
     *
     * @param value      待加密字符串
     * @param encodeType 加密后的编码格式
     */
    @Override
    public String encrypt(String value, EncodeType encodeType) {
        return EncryptUtils.encryptByBase64(value);
    }

    /**
     * 解密
     *
     * @param value 待加密字符串
     */
    @Override
    public String decrypt(String value) {
        return EncryptUtils.decryptByBase64(value);
    }
}
