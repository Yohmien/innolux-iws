/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process HTTP fixture 测试专用登录；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.satoken.utils.LoginHelper;
import com.innolux.iws.system.api.model.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仅测试类路径可用的登录入口，不读取生产用户、客户端或菜单表。
 */
@SaIgnore
@RestController
@RequestMapping
public class ProcessHttpFixtureAuthController {

    private static final String FIXTURE_PASSWORD = "fixture";
    private static final String FIXTURE_CLIENT_ID = "0f7a0c5f3b2c4d6e8a1b9c7d5e3f2a10";

    private static final Set<String> ALL_PERMISSIONS = Set.of(
        "process:schedule:list", "process:schedule:query", "process:schedule:add",
        "process:schedule:edit", "process:schedule:remove", "process:schedule:pause",
        "process:schedule:resume", "process:schedule:restart",
        "process:definition:list", "process:definition:query", "process:definition:add",
        "process:definition:edit", "process:definition:copy", "process:definition:publish",
        "process:definition:remove", "process:execution:list", "process:request:list",
        "process:request:query", "process:instance:query", "process:instance:chain",
        "process:instance:attach", "process:instance:confirm", "process:instance:pause",
        "process:instance:resume", "process:instance:cancel", "process:instance:hold",
        "process:instance:release", "process:instance:priority", "process:instance:execute",
        "process:trigger:receive", "process:event:query", "process:event:retry",
        "process:event:finalize", "process:event:revalidate", "process:event:priority",
        "process:event:request", "process:audit:query", "process:object:all"
    );

    /**
     * Web 登录路径的测试响应。
     *
     * @param body 测试登录请求
     * @return token
     */
    @PostMapping("/auth/login")
    public R<FixtureLoginResult> login(@RequestBody Map<String, Object> body) {
        return loginInternal(body);
    }

    /**
     * 显式 fixture 登录入口，便于自动化。
     *
     * @param body 测试登录请求
     * @return token
     */
    @PostMapping("/test-auth/login")
    public R<FixtureLoginResult> fixtureLogin(@RequestBody Map<String, Object> body) {
        return loginInternal(body);
    }

    /**
     * 测试验证码关闭响应。
     *
     * @return 关闭状态
     */
    @GetMapping("/auth/code")
    public R<Map<String, Object>> captcha() {
        return R.ok(Map.of("captchaEnabled", false));
    }

    /**
     * 注销当前真实 SaToken 会话。
     *
     * @return 操作结果
     */
    @PostMapping("/auth/logout")
    public R<Void> logout() {
        StpUtil.logout();
        return R.ok();
    }

    private R<FixtureLoginResult> loginInternal(Map<String, Object> body) {
        String username = stringValue(body.get("username"));
        String password = stringValue(body.get("password"));
        if (!FIXTURE_PASSWORD.equals(password)) {
            return R.fail("fixture 密码错误");
        }
        FixtureProfile profile = FixtureProfile.fromUsername(username);
        if (profile == null) {
            return R.fail("未知 fixture 用户");
        }

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(profile.userId());
        loginUser.setUsername(username);
        loginUser.setNickname(profile.displayName());
        loginUser.setUserType("sys_user");
        loginUser.setDeptId(100L);
        loginUser.setDeptName("Process Fixture");
        loginUser.setMenuPermission(profile.permissions());
        loginUser.setRolePermission(Set.of("process_fixture"));
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(System.currentTimeMillis() + 60L * 60 * 1000);

        SaLoginParameter parameter = new SaLoginParameter();
        parameter.setDeviceType("PC");
        parameter.setExtra(LoginHelper.CLIENT_KEY, FIXTURE_CLIENT_ID);
        parameter.setExtra(LoginHelper.CLIENT_ACCESS_PATH_KEY,
            "/process/**,/system/**,/resource/**,/test-auth/**");
        LoginHelper.login(loginUser, parameter);

        return R.ok(new FixtureLoginResult(StpUtil.getTokenValue(), FIXTURE_CLIENT_ID,
            StpUtil.getTokenTimeout(), profile.permissions().stream().sorted().toList()));
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 测试登录响应，字段名保持前端登录契约。
     *
     * @param access_token 访问令牌
     * @param clientid 客户端标识
     * @param expire_in 有效期秒数
     * @param permissions 权限集合
     */
    public record FixtureLoginResult(String access_token, String clientid, long expire_in,
                                     List<String> permissions) {
    }

    private record FixtureProfile(long userId, String displayName, Set<String> permissions) {

        private static FixtureProfile fromUsername(String username) {
            if ("fixture-admin".equals(username) || "fixtureadmin".equals(username)) {
                return new FixtureProfile(9001L, "Process 全权限", ALL_PERMISSIONS);
            }
            if ("fixture-list-only".equals(username) || "listonly".equals(username)) {
                return new FixtureProfile(9002L, "Process 调度列表", Set.of("process:schedule:list"));
            }
            return null;
        }
    }
}
