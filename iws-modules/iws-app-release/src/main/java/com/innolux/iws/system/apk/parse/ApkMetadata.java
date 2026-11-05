/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : APK 安装包元数据模型；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.parse;

import java.util.regex.Pattern;

/**
 * APK 安装包的应用展示名称、包名和版本信息。
 *
 * <p>元数据在构造时完成校验。版本码使用非负有符号 {@code long}，
 * 高位来自 Android Manifest 的 {@code versionCodeMajor}。</p>
 *
 * @param appName     Android 应用标签
 * @param packageName Android 应用包名
 * @param versionName Android 版本名称
 * @param versionCode 非负版本码
 */
public record ApkMetadata(String appName, String packageName, String versionName, Long versionCode) {

    private static final Pattern ANDROID_PACKAGE_NAME =
        Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private static final String UNRESOLVED_RESOURCE_ID_PREFIX = "resourceId:0x";

    /**
     * 创建并校验 APK 元数据。
     *
     * @throws IllegalArgumentException 应用名称、包名、版本名称或版本码缺失，
     *                                  应用名称仍为未解析资源，包名非法或版本码为负数
     */
    public ApkMetadata {
        appName = requireText(appName, "APK 应用名称缺失");
        packageName = requireText(packageName, "APK 包名缺失");
        versionName = requireText(versionName, "APK 版本名称缺失");
        if (versionCode == null) {
            throw new IllegalArgumentException("APK 版本码缺失");
        }
        if (versionCode < 0) {
            throw new IllegalArgumentException("APK 版本码不能为负数");
        }
        if (isUnresolvedResource(appName)) {
            throw new IllegalArgumentException("APK 应用名称资源无法解析");
        }
        if (!ANDROID_PACKAGE_NAME.matcher(packageName).matches()) {
            throw new IllegalArgumentException("APK 包名格式非法");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static boolean isUnresolvedResource(String appName) {
        String normalized = appName.strip();
        return normalized.startsWith(UNRESOLVED_RESOURCE_ID_PREFIX)
            || normalized.startsWith("@string/")
            || normalized.startsWith("@android:string/");
    }
}
