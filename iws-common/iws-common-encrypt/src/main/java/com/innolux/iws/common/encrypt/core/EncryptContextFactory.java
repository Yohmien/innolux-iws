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

package com.innolux.iws.common.encrypt.core;

import com.innolux.iws.common.core.utils.StringUtils;
import com.innolux.iws.common.encrypt.annotation.EncryptField;
import com.innolux.iws.common.encrypt.enums.AlgorithmType;
import com.innolux.iws.common.encrypt.enums.EncodeType;
import com.innolux.iws.common.encrypt.properties.EncryptorProperties;

import java.lang.reflect.Field;

/**
 * 加密上下文工厂。
 *
 * @author YONGJIE.PENG
 */
public class EncryptContextFactory {

    private final EncryptorProperties defaultProperties;

    /**
     * 构造加密上下文工厂。
     *
     * @param defaultProperties 默认加密配置
     */
    public EncryptContextFactory(EncryptorProperties defaultProperties) {
        this.defaultProperties = defaultProperties;
    }

    /**
     * 根据字段注解和默认配置创建加密上下文。
     *
     * @param field 加密字段
     * @return 加密上下文
     */
    public EncryptContext create(Field field) {
        EncryptField encryptField = field.getAnnotation(EncryptField.class);
        EncryptContext encryptContext = new EncryptContext();
        encryptContext.setAlgorithm(encryptField.algorithm() == AlgorithmType.DEFAULT ? defaultProperties.getAlgorithm() : encryptField.algorithm());
        encryptContext.setEncode(encryptField.encode() == EncodeType.DEFAULT ? defaultProperties.getEncode() : encryptField.encode());
        encryptContext.setPassword(StringUtils.isBlank(encryptField.password()) ? defaultProperties.getPassword() : encryptField.password());
        encryptContext.setPrivateKey(StringUtils.isBlank(encryptField.privateKey()) ? defaultProperties.getPrivateKey() : encryptField.privateKey());
        encryptContext.setPublicKey(StringUtils.isBlank(encryptField.publicKey()) ? defaultProperties.getPublicKey() : encryptField.publicKey());
        return encryptContext;
    }
}
