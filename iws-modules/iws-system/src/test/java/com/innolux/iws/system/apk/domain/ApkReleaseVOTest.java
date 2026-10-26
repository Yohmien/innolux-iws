/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS System 模块
 * @Version      : V1.0
 * @Description  : 验证 APK 发布版本的长整型标识和版本号序列化；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.apk.domain;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.system.apk.domain.vo.ApkReleaseVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * APK 发布版本 JSON 契约测试。
 */
@DisplayName("APK 发布版本 JSON")
@Tag("dev")
class ApkReleaseVOTest {

    @Test
    @DisplayName("长整型标识和版本号均输出字符串")
    void serializesIdentifiersAsStrings() throws Exception {
        ApkRelease release = new ApkRelease();
        release.setReleaseId(1L);
        release.setOssId(2L);
        release.setProcessInstanceId(3L);
        release.setVersionCode(4L);
        release.setPackageName("com.demo.app");
        release.setForceUpdate(false);

        String json = new ObjectMapper().writeValueAsString(ApkReleaseVO.from(release));

        assertTrue(json.contains("\"releaseId\":\"1\""));
        assertTrue(json.contains("\"ossId\":\"2\""));
        assertTrue(json.contains("\"processInstanceId\":\"3\""));
        assertTrue(json.contains("\"versionCode\":\"4\""));
        assertTrue(json.contains("\"forceUpdate\":false"));
    }

    @Test
    @DisplayName("最新版本为空时成功响应显式输出 data:null")
    void serializesNullLatestReleaseData() throws Exception {
        R<ApkReleaseVO> response = R.ok((ApkReleaseVO) null);

        String json = new ObjectMapper().writeValueAsString(response);

        assertTrue(json.contains("\"data\":null"));
    }
}
