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

package com.innolux.iws.system.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.innolux.iws.system.domain.SysOss;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * OSS对象存储分页查询对象 sys_oss
 *
 * @author YONGJIE.PENG
 */
@Data
@AutoMapper(target = SysOss.class, reverseConvertGenerate = false)
public class SysOssBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * ossId
     */
    private Long ossId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 原名
     */
    private String originalName;

    /**
     * 文件后缀名
     */
    private String fileSuffix;

    /**
     * URL地址
     */
    private String url;

    /**
     * 扩展字段
     */
    private String ext1;

    /**
     * 服务商
     */
    private String service;

    /**
     * 创建者
     */
    private Long createBy;

    /**
     * 请求参数
     */
    private Map<String, Object> params = new HashMap<>();

}
