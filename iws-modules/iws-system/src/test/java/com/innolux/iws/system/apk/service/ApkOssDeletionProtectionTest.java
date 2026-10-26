/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : APK 应用管理
 * @Version      : V1.0
 * @Description  : 校验安装包删除保护在整批删除前生效；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.system.apk.service;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.system.domain.SysOss;
import com.innolux.iws.system.mapper.SysOssMapper;
import com.innolux.iws.system.service.impl.SysOssServiceImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** 文件管理入口不能删除应用包管理的安装包。 */
@Tag("dev")
class ApkOssDeletionProtectionTest {
    @Test
    void rejectsEntireBatchBeforeDeletingAnyObject() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        new cn.hutool.extra.spring.SpringUtil().postProcessBeanFactory(beans);
        SysOssMapper mapper = mock(SysOssMapper.class);
        SysOss normal = new SysOss();
        normal.setOssId(1L);
        normal.setExt1("{}");
        SysOss apk = new SysOss();
        apk.setOssId(2L);
        apk.setExt1("{\"bizType\":\"APK_RELEASE\"}");
        List<Long> ids = List.of(1L, 2L);
        when(mapper.selectByIds(ids)).thenReturn(List.of(normal, apk));
        assertThrows(ServiceException.class, () -> new SysOssServiceImpl(mapper).deleteWithValidByIds(ids, true));
        verify(mapper, never()).deleteByIds(ids);
    }
}
