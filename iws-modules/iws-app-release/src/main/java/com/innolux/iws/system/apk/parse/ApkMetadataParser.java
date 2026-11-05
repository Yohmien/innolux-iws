/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 从 APK 二进制 Manifest 和资源表解析应用元数据；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.parse;

import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * 读取 APK 安装包的应用元数据。
 *
 * <p>组件本身不保存解析状态。每次调用仅读取二进制 Manifest 和资源表，
 * 不依赖文件名，也不执行 APK 中的代码或签名校验。</p>
 */
@Component
public class ApkMetadataParser {

    /**
     * 解析 APK 文件中的应用元数据。
     *
     * @param apkFile 待解析的本地 APK 文件
     * @return 已校验的应用名称、包名和版本信息
     * @throws IllegalArgumentException 文件为空、不是普通文件，或 APK 元数据不符合要求
     * @throws IOException              文件无法读取或 APK 结构无法解析
     */
    public ApkMetadata parse(File apkFile) throws IOException {
        if (apkFile == null) {
            throw new IllegalArgumentException("APK 文件不能为空");
        }
        if (!apkFile.isFile()) {
            throw new IllegalArgumentException("APK 文件不存在或不是普通文件");
        }
        try (ApkFile parser = new ApkFile(apkFile)) {
            ApkMeta apkMeta = parser.getApkMeta();
            if (apkMeta == null) {
                throw new IllegalArgumentException("APK 元数据缺失");
            }
            return new ApkMetadata(
                apkMeta.getLabel(),
                apkMeta.getPackageName(),
                apkMeta.getVersionName(),
                apkMeta.getVersionCode()
            );
        }
    }
}
