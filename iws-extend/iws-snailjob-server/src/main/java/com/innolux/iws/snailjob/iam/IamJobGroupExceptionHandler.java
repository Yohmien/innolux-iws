/*
 * @Date         : 2026-11-04
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : Job 统一身份接入
 * @Version      : V1.0
 * @Description  : 输出 Job group 越权的明确 403 响应；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.snailjob.iam;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Job group 授权异常响应。
 */
@RestControllerAdvice
public class IamJobGroupExceptionHandler {

    @ExceptionHandler(IamJobGroupAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(IamJobGroupAccessDeniedException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("msg", error.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
