/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint HTTP fixture 测试专用登录；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.http;

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
 * 仅测试类路径可用的登录入口。
 *
 * <p>登录使用真实 SaToken 会话；测试用户权限通过 {@code SaPermissionImpl} 在请求时校验。</p>
 */
@SaIgnore
@RestController
@RequestMapping
public class EndpointHttpFixtureAuthController {

    private static final String FIXTURE_PASSWORD = "fixture";
    private static final String FIXTURE_CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final Set<String> ALL_PERMISSIONS = Set.of(
        "endpoint:system:list", "endpoint:system:query", "endpoint:system:add", "endpoint:system:edit",
        "endpoint:environment:list", "endpoint:environment:query", "endpoint:environment:add",
        "endpoint:environment:edit", "endpoint:address:list", "endpoint:address:query",
        "endpoint:address:add", "endpoint:address:edit", "endpoint:audit:query"
    );

    /**
     * Web 登录路径的测试响应，不读取生产客户端或用户表。
     *
     * @param body 测试登录请求
     * @return token
     */
    @PostMapping("/auth/login")
    public R<FixtureLoginResult> login(@RequestBody Map<String, Object> body) {
        return loginInternal(body);
    }

    /**
     * 显式 fixture 登录入口，便于自动化与浏览器会话注入。
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
        loginUser.setDeptName("Endpoint Fixture");
        loginUser.setMenuPermission(profile.permissions());
        loginUser.setRolePermission(Set.of("endpoint_fixture"));
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(System.currentTimeMillis() + 60L * 60 * 1000);

        SaLoginParameter parameter = new SaLoginParameter();
        parameter.setDeviceType("PC");
        parameter.setExtra(LoginHelper.CLIENT_KEY, FIXTURE_CLIENT_ID);
        parameter.setExtra(LoginHelper.CLIENT_ACCESS_PATH_KEY, "/endpoint/**,/system/**");
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
            if ("fixture-admin".equals(username)) {
                return new FixtureProfile(9001L, "Endpoint 全权限", ALL_PERMISSIONS);
            }
            if ("fixtureadmin".equals(username)) {
                return new FixtureProfile(9001L, "Endpoint 全权限", ALL_PERMISSIONS);
            }
            if ("fixture-system-reader".equals(username)) {
                return new FixtureProfile(9002L, "Endpoint 系统只读",
                    Set.of("endpoint:system:list", "endpoint:system:query"));
            }
            if ("systemreader".equals(username)) {
                return new FixtureProfile(9002L, "Endpoint 系统只读",
                    Set.of("endpoint:system:list", "endpoint:system:query"));
            }
            if ("fixture-audit-reader".equals(username)) {
                return new FixtureProfile(9003L, "Endpoint 审计只读", Set.of("endpoint:audit:query"));
            }
            if ("auditreader".equals(username)) {
                return new FixtureProfile(9003L, "Endpoint 审计只读", Set.of("endpoint:audit:query"));
            }
            if ("fixture-list-only".equals(username)) {
                return new FixtureProfile(9004L, "Endpoint 系统列表", Set.of("endpoint:system:list"));
            }
            if ("listonly".equals(username)) {
                return new FixtureProfile(9004L, "Endpoint 系统列表", Set.of("endpoint:system:list"));
            }
            return null;
        }
    }
}
