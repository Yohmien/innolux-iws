/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : IWS 主站当前用户只读适配；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.controller;

import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.mybatis.helper.DataPermissionHelper;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.domain.vo.UserInfoVo;
import com.innolux.iws.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IWS 当前用户信息。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/user")
public class IwsCurrentUserController {

    private final ISysUserService userService;

    /**
     * 获取当前 IWS 用户信息、角色与权限集合。
     *
     * @return 当前用户上下文
     */
    @GetMapping("/getInfo")
    public R<UserInfoVo> getInfo() {
        UserInfoVo userInfoVo = new UserInfoVo();
        LoginUser loginUser = LoginHelper.getLoginUser();
        SysUserVo user = DataPermissionHelper.ignore(() -> userService.selectUserById(loginUser.getUserId()));
        if (ObjectUtil.isNull(user)) {
            return R.fail("没有权限访问用户数据!");
        }
        userInfoVo.setUser(user);
        userInfoVo.setPermissions(loginUser.getMenuPermission());
        userInfoVo.setRoles(loginUser.getRolePermission());
        return R.ok(userInfoVo);
    }
}
