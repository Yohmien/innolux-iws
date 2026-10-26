/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 初始化 APK 测试所需的 MyBatis-Plus 实体元数据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.innolux.iws.system.apk.domain.ApkRelease;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * APK 纯单元测试的 MyBatis-Plus 实体元数据初始化。
 */
final class ApkMybatisTestSupport {

    private ApkMybatisTestSupport() {
    }

    /**
     * 初始化发布版本 Lambda 列缓存。
     */
    static synchronized void initialize() {
        if (TableInfoHelper.getTableInfo(ApkRelease.class) == null) {
            TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "apk-test"), ApkRelease.class);
        }
    }
}
