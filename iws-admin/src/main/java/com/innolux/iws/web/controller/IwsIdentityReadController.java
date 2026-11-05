/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.0
 * @Description  : 主站业务页面所需的用户、角色、部门、岗位与菜单只读选项；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.web.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.domain.bo.SysDeptBo;
import com.innolux.iws.system.domain.bo.SysMenuBo;
import com.innolux.iws.system.domain.bo.SysPostBo;
import com.innolux.iws.system.domain.bo.SysUserBo;
import com.innolux.iws.system.domain.vo.SysDeptVo;
import com.innolux.iws.system.domain.vo.SysMenuVo;
import com.innolux.iws.system.domain.vo.SysPostVo;
import com.innolux.iws.system.domain.vo.SysRoleVo;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.ISysDeptService;
import com.innolux.iws.system.service.ISysMenuService;
import com.innolux.iws.system.service.ISysPostService;
import com.innolux.iws.system.service.ISysRoleService;
import com.innolux.iws.system.service.ISysUserService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * IWS 业务只读身份选项。
 */
@Validated
@RestController
@RequiredArgsConstructor
public class IwsIdentityReadController {

    private final ISysUserService userService;
    private final ISysRoleService roleService;
    private final ISysDeptService deptService;
    private final ISysPostService postService;
    private final ISysMenuService menuService;

    /**
     * 分页查询用户，仅供原业务选择器读取。
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/system/user/list")
    public R<PageResult<SysUserVo>> listUser(SysUserBo user, PageQuery pageQuery) {
        return R.ok(userService.selectPageUserList(user, pageQuery));
    }

    /**
     * 批量读取用户基础信息。
     */
    @SaCheckPermission("system:user:query")
    @GetMapping("/system/user/optionselect")
    public R<List<SysUserVo>> userOptions(@RequestParam(required = false) Long[] userIds,
                                          @RequestParam(required = false) Long deptId) {
        return R.ok(userService.selectUserByIds(ArrayUtil.isEmpty(userIds) ? null : List.of(userIds), deptId));
    }

    /**
     * 获取用户筛选部门树。
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/system/user/deptTree")
    public R<List<Tree<Long>>> userDeptTree(SysDeptBo dept) {
        return R.ok(deptService.selectDeptTreeList(dept));
    }

    /**
     * 获取指定部门下有效用户。
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/system/user/list/dept/{deptId}")
    public R<List<SysUserVo>> usersByDept(@PathVariable @NotNull Long deptId) {
        return R.ok(userService.selectUserListByDept(deptId));
    }

    /**
     * 读取有效角色选项。
     */
    @SaCheckPermission("system:role:query")
    @GetMapping("/system/role/optionselect")
    public R<List<SysRoleVo>> roleOptions(@RequestParam(required = false) Long[] roleIds) {
        return R.ok(roleService.selectRoleByIds(roleIds == null ? null : List.of(roleIds)));
    }

    /**
     * 读取部门列表供业务选择器使用。
     */
    @SaCheckPermission("system:dept:list")
    @GetMapping("/system/dept/list")
    public R<List<SysDeptVo>> deptList(SysDeptBo dept) {
        return R.ok(deptService.selectDeptList(dept));
    }

    /**
     * 读取岗位选项。
     */
    @SaCheckPermission("system:post:query")
    @GetMapping("/system/post/optionselect")
    public R<List<SysPostVo>> postOptions(@RequestParam(required = false) Long[] postIds,
                                          @RequestParam(required = false) Long deptId) {
        if (ObjectUtil.isNotNull(deptId)) {
            SysPostBo post = new SysPostBo();
            post.setDeptId(deptId);
            return R.ok(postService.selectPostList(post));
        }
        return R.ok(postIds == null ? new ArrayList<>() : postService.selectPostByIds(List.of(postIds)));
    }

    /**
     * 读取岗位筛选部门树。
     */
    @SaCheckPermission("system:post:list")
    @GetMapping("/system/post/deptTree")
    public R<List<Tree<Long>>> postDeptTree(SysDeptBo dept) {
        return R.ok(deptService.selectDeptTreeList(dept));
    }

    /**
     * 读取菜单列表，供主站代码生成等只读业务使用。
     */
    @SaCheckPermission("system:menu:list")
    @GetMapping("/system/menu/list")
    public R<List<SysMenuVo>> menuList(SysMenuBo menu) {
        return R.ok(menuService.selectMenuList(menu, LoginHelper.getUserId()));
    }

    /**
     * 读取菜单下拉树。
     */
    @SaCheckPermission("system:menu:query")
    @GetMapping("/system/menu/treeselect")
    public R<List<Tree<Long>>> menuTree(SysMenuBo menu) {
        List<SysMenuVo> menus = menuService.selectMenuList(menu, LoginHelper.getUserId());
        return R.ok(menuService.buildMenuTreeSelect(menus));
    }
}
