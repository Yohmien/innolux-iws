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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Get文件对象结果
 *
 * @param key
 * @param eTag
 * @param lastModified
 * @param size
 * @param contentType
 * @param contentDisposition
 * @param contentRange
 * @param contentEncoding
 * @param contentLanguage
 * @param metadata
 * @author YONGJIE.PENG
 */
public record GetObjectResult(
    String key,
    String eTag,
    LocalDateTime lastModified,
    long size,
    String contentType,
    String contentDisposition,
    String contentRange,
    String contentEncoding,
    String contentLanguage,
    Map<String, String> metadata
) {

    /**
     * 构建文件对象获取结果。
     *
     * @param key                对象 Key
     * @param eTag               对象 ETag
     * @param lastModified       最后修改时间
     * @param size               对象大小
     * @param contentType        内容类型
     * @param contentDisposition 内容处置方式
     * @param contentRange       内容范围
     * @param contentEncoding    内容编码
     * @param contentLanguage    内容语言
     * @param metadata           自定义元数据
     * @return 文件对象获取结果
     */
    public static GetObjectResult form(String key, String eTag, LocalDateTime lastModified, long size
        , String contentType, String contentDisposition, String contentRange, String contentEncoding, String contentLanguage
        , Map<String, String> metadata) {
        return new GetObjectResult(key, eTag, lastModified, size, contentType, contentDisposition, contentRange, contentEncoding, contentLanguage, metadata);
    }

}
