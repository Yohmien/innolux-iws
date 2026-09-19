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

package com.innolux.iws.system.controller.monitor;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.lock.annotation.Lock4j;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.constant.CacheNames;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.excel.utils.ExcelBuilder;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.system.domain.bo.SysLoginInfoBo;
import com.innolux.iws.system.domain.vo.SysLoginInfoVo;
import com.innolux.iws.system.service.ISysLoginInfoService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统访问记录
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/monitor/loginInfo")
public class SysLoginInfoController extends BaseController {

    private final ISysLoginInfoService loginInfoService;

    /**
     * 分页查询系统访问记录。
     *
     * @param loginInfo 查询条件
     * @param pageQuery 分页参数
     * @return 登录日志分页结果
     */
    @SaCheckPermission("monitor:logininfo:list")
    @GetMapping("/list")
    public R<PageResult<SysLoginInfoVo>> list(SysLoginInfoBo loginInfo, PageQuery pageQuery) {
        return R.ok(loginInfoService.selectPageLoginInfoList(loginInfo, pageQuery));
    }

    /**
     * 导出系统访问记录列表。
     *
     * @param loginInfo 查询条件
     * @param response  HTTP 响应
     */
    @Log(title = "登录日志", businessType = BusinessType.EXPORT)
    @SaCheckPermission("monitor:logininfo:export")
    @PostMapping("/export")
    public void export(SysLoginInfoBo loginInfo, HttpServletResponse response) {
        List<SysLoginInfoVo> list = loginInfoService.selectLoginInfoList(loginInfo);
        ExcelBuilder.of(list, SysLoginInfoVo.class).sheetName("登录日志").toResponse(response);
    }

    /**
     * 批量删除登录日志
     *
     * @param infoIds 日志ids
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:remove")
    @Log(title = "登录日志", businessType = BusinessType.DELETE)
    @DeleteMapping("/{infoIds}")
    public R<Void> remove(@PathVariable Long[] infoIds) {
        return toAjax(loginInfoService.deleteLoginInfoByIds(infoIds));
    }

    /**
     * 清空系统访问记录。
     *
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:remove")
    @Log(title = "登录日志", businessType = BusinessType.CLEAN)
    @Lock4j
    @DeleteMapping("/clean")
    public R<Void> clean() {
        loginInfoService.cleanLoginInfo();
        return R.ok();
    }

    /**
     * 清除指定用户的登录失败锁定状态。
     *
     * @param userName 用户名
     * @return 操作结果
     */
    @SaCheckPermission("monitor:logininfo:unlock")
    @Log(title = "账户解锁", businessType = BusinessType.OTHER)
    @RepeatSubmit()
    @GetMapping("/unlock/{userName}")
    public R<Void> unlock(@PathVariable("userName") String userName) {
        String loginName = CacheNames.PWD_ERR_CNT_KEY + userName;
        if (RedisUtils.hasKey(loginName)) {
            RedisUtils.deleteObject(loginName);
        }
        return R.ok();
    }

}
