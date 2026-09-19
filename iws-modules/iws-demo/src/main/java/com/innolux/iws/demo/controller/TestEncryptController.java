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

package com.innolux.iws.demo.controller;

import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.demo.domain.TestDemoEncrypt;
import com.innolux.iws.demo.mapper.TestDemoEncryptMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;


/**
 * 测试数据库加解密功能
 *
 * @author YONGJIE.PENG
 */
@Validated
@RestController
@RequestMapping("/demo/encrypt")
public class TestEncryptController {

    @Autowired
    private TestDemoEncryptMapper mapper;
    @Value("${mybatis-encryptor.enable}")
    private Boolean encryptEnable;

    /**
     * 测试数据库加解密
     *
     * @param key   测试key
     * @param value 测试value
     */
    @GetMapping()
    public R<Map<String, TestDemoEncrypt>> test(String key, String value) {
        if (!encryptEnable) {
            throw new RuntimeException("加密功能未开启!");
        }
        Map<String, TestDemoEncrypt> map = new HashMap<>(2);
        TestDemoEncrypt demo = new TestDemoEncrypt();
        demo.setTestKey(key);
        demo.setValue(value);
        mapper.insert(demo);
        map.put("加密", demo);
        TestDemoEncrypt testDemo = mapper.selectById(demo.getId());
        map.put("解密", testDemo);
        return R.ok(map);
    }


}
