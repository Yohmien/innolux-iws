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

package com.innolux.iws.common.core.exception.file;

import com.innolux.iws.common.core.exception.base.BaseException;

import java.io.Serial;

/**
 * 文件信息异常类
 *
 * @author YONGJIE.PENG
 */
public class FileException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造文件异常。
     *
     * @param code 错误码
     * @param args 错误码参数
     */
    public FileException(String code, Object[] args) {
        super("file", code, args, null);
    }

}
