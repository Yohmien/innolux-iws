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

package com.innolux.iws.common.oss.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import com.innolux.iws.common.oss.exception.S3StorageException;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;

import java.util.Arrays;

/**
 * 访问策略
 *
 * @author YONGJIE.PENG
 */
@Getter
@AllArgsConstructor
public enum AccessPolicy {

    /**
     * 私有
     */
    PRIVATE(0, BucketCannedACL.PRIVATE, ObjectCannedACL.PRIVATE),

    /**
     * 公有读写
     */
    PUBLIC_READ_WRITE(1, BucketCannedACL.PUBLIC_READ_WRITE, ObjectCannedACL.PUBLIC_READ_WRITE),

    /**
     * 公有只读
     */
    PUBLIC_READ(2, BucketCannedACL.PUBLIC_READ, ObjectCannedACL.PUBLIC_READ);

    /**
     * 访问策略类型
     */
    private final Integer type;

    /**
     * 桶权限
     */
    private final BucketCannedACL bucketCannedACL;

    /**
     * 文件对象权限
     */
    private final ObjectCannedACL objectCannedACL;

    /**
     * 根据策略类型查找访问策略。
     *
     * @param type 策略类型
     * @return 访问策略
     */
    public static AccessPolicy formType(String type) {
        return Arrays.stream(values())
            .filter(policy -> policy.getType().toString().equals(type))
            .findFirst()
            .orElseThrow(() -> S3StorageException.form("'type' not found By " + type));
    }
}
