/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 二进制 Manifest、资源标签和版本元数据解析；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * APK 元数据解析测试。
 */
@DisplayName("APK 元数据解析")
@Tag("dev")
class ApkMetadataParserTest {

    private static final String FIXTURE_PATH = "/apk/probe.apk.b64";

    private final ApkMetadataParser parser = new ApkMetadataParser();

    @Test
    @DisplayName("从二进制 Manifest 和资源表读取元数据")
    void parsesMetadataFromApkContent(@TempDir Path tempDir) throws IOException {
        Path apkFile = tempDir.resolve("renamed-upload.txt");
        Files.write(apkFile, fixtureBytes());

        ApkMetadata metadata = parser.parse(apkFile.toFile());

        assertEquals("Probe App", metadata.appName());
        assertEquals("com.example.probe", metadata.packageName());
        assertEquals("1.2.3", metadata.versionName());
        assertEquals((2L << 32) | 42L, metadata.versionCode());
    }

    @Test
    @DisplayName("无法读取的容器保持 IO 异常")
    void rejectsNonApkContainer(@TempDir Path tempDir) throws IOException {
        Path notApk = tempDir.resolve("broken.apk");
        Files.writeString(notApk, "not an apk");

        assertThrows(IOException.class, () -> parser.parse(notApk.toFile()));
    }

    @Test
    @DisplayName("空文件参数或非普通文件被拒绝")
    void rejectsMissingFile(@TempDir Path tempDir) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(tempDir.resolve("missing.apk").toFile()));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse(tempDir.toFile()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMetadata")
    @DisplayName("拒绝缺失、未解析或非法的元数据")
    void rejectsInvalidMetadata(String scenarioName, String appName, String packageName,
                                String versionName, Long versionCode) {
        assertThrows(IllegalArgumentException.class,
            () -> new ApkMetadata(appName, packageName, versionName, versionCode));
    }

    static Stream<Arguments> invalidMetadata() {
        return Stream.of(
            Arguments.of("应用名称为空", " ", "com.example.demo", "1.0", 1L),
            Arguments.of("应用名称为空值", null, "com.example.demo", "1.0", 1L),
            Arguments.of("应用名称资源未解析", "resourceId:0x7f010001", "com.example.demo", "1.0", 1L),
            Arguments.of("应用名称字符串资源未解析", "@string/app_name", "com.example.demo", "1.0", 1L),
            Arguments.of("包名为空", "Demo", " ", "1.0", 1L),
            Arguments.of("包名为空值", "Demo", null, "1.0", 1L),
            Arguments.of("包名仅单段", "Demo", "demo", "1.0", 1L),
            Arguments.of("包名含空段", "Demo", "com..demo", "1.0", 1L),
            Arguments.of("包名段以数字开头", "Demo", "com.1demo", "1.0", 1L),
            Arguments.of("包名含连字符", "Demo", "com.example-demo", "1.0", 1L),
            Arguments.of("版本名称为空", "Demo", "com.example.demo", " ", 1L),
            Arguments.of("版本名称为空值", "Demo", "com.example.demo", null, 1L),
            Arguments.of("版本码为空值", "Demo", "com.example.demo", "1.0", null),
            Arguments.of("版本码为负数", "Demo", "com.example.demo", "1.0", -1L)
        );
    }

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream stream = Objects.requireNonNull(
            ApkMetadataParserTest.class.getResourceAsStream(FIXTURE_PATH), FIXTURE_PATH)) {
            return Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
    }
}
