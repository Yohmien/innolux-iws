/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 认证中心复用原账号页面所需的字典数据只读接口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.system.controller.system;

import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.system.domain.vo.SysDictDataVo;
import com.innolux.iws.system.service.ISysDictTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证中心字典数据只读接口。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/dict/data")
public class SysDictDataController {

    private final ISysDictTypeService dictTypeService;

    /**
     * 根据字典类型查询字典数据。
     *
     * @param dictType 字典类型
     * @return 字典数据列表
     */
    @GetMapping("/type/{dictType}")
    public R<List<SysDictDataVo>> dictType(@PathVariable String dictType) {
        List<SysDictDataVo> data = dictTypeService.selectDictDataByType(dictType);
        return R.ok(ObjectUtil.isNull(data) ? new ArrayList<>() : data);
    }
}
