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

package com.innolux.iws.common.oss.model;

/**
 * Put文件对象结果
 *
 * @param url
 * @param key
 * @param eTag
 * @param size
 * @author YONGJIE.PENG
 */
public record PutObjectResult(
    String url,
    String key,
    String eTag,
    long size
) {

    /**
     * 构建文件上传结果。
     *
     * @param url  文件地址
     * @param key  文件标识
     * @param eTag 文件 ETag
     * @param size 文件大小
     * @return 文件上传结果
     */
    public static PutObjectResult form(String url, String key, String eTag, long size) {
        return new PutObjectResult(url, key, eTag, size);
    }

}
