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

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import software.amazon.awssdk.transfer.s3.progress.TransferListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 可选项
 *
 * @author YONGJIE.PENG
 */
@Data
@EqualsAndHashCode
@Accessors(chain = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Options {

    /**
     * 文件长度
     */
    private Long length;

    /**
     * 文件MD5摘要
     */
    private String md5Digest;

    /**
     * 内容类型
     */
    private String contentType;

    /**
     * 元数据
     */
    private Map<String, String> metadata;

    /**
     * 传输监听器
     */
    private Collection<TransferListener> transferListeners;

    /**
     * 添加元数据项
     */
    public Options addMetadataItem(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }

    /**
     * 添加监听器
     */
    public Options addTransferListener(TransferListener transferListener) {
        if (this.transferListeners == null) {
            this.transferListeners = new ArrayList<>();
        }
        this.transferListeners.add(transferListener);
        return this;
    }

    /**
     * 创建可选项对象
     */
    public static Options builder() {
        return create();
    }

    /**
     * 创建可选项对象
     */
    public static Options create() {
        return new Options();
    }
}
