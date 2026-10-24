/*
 * @Date         : 2026-10-22
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 系统接入配置
 * @Version      : V1.0
 * @Description  : endpoint 真实 HTTP、MVC 与 SaToken 鉴权集成测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.endpoint.http;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * endpoint 真实 HTTP、MVC 与 SaToken 鉴权集成测试。
 *
 * <p>使用随机端口和内嵌 Jetty，测试数据写入 13307 隔离实例中固定的 _test 库。
 * 默认跳过；显式设置 {@code -Diws.endpoint.test.enabled=true} 后才执行。</p>
 */
@Tag("dev")
@EnabledIf("fixtureEnabled")
@SpringBootTest(
    classes = EndpointHttpFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "server.port=0",
        "spring.main.banner-mode=off",
        "spring.jmx.enabled=false",
        "xss.enabled=false",
        "iws.endpoint.enabled=true",
        "iws.endpoint.current-environment=HTTP_ENV",
        "sa-token.token-name=Authorization",
        "sa-token.is-concurrent=true",
        "sa-token.is-share=false",
        "sa-token.token-prefix=Bearer",
        "sa-token.jwt-secret-key=endpoint-http-fixture-secret",
        "security.excludes[0]=/auth/**",
        "security.excludes[1]=/test-auth/**",
        "security.excludes[2]=/error",
        "logging.level.root=warn"
    }
)
class EndpointHttpIntegrationTest {

    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final String FIXTURE_PASSWORD = "fixture";

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpClient httpClient;
    private String baseUrl;

    /**
     * 仅显式 opt-in 时运行。
     *
     * @return true 表示允许真实隔离库测试
     */
    static boolean fixtureEnabled() {
        return Boolean.parseBoolean(System.getProperty("iws.endpoint.test.enabled", "false"));
    }

    @BeforeEach
    void resetFixture() throws SQLException {
        EndpointHttpFixtureDatabase.resetSchema();
        baseUrl = "http://127.0.0.1:" + port;
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        System.out.println("IWS_ENDPOINT_HTTP_FIXTURE_RUNNING_URL=" + baseUrl);
    }

    @AfterAll
    static void cleanFixture() throws SQLException {
        if (fixtureEnabled()) {
            EndpointHttpFixtureDatabase.dropSchema();
        }
    }

    @Test
    void unauthenticatedRequestIsRejectedByRealSaTokenInterceptor() throws Exception {
        HttpResult result = get("/endpoint/systems/page", null);

        assertThat(result.body().path("code").asInt()).isEqualTo(401);
        assertThat(result.rawBody()).contains("登录状态异常");
    }

    @Test
    void wrongPermissionAndDirectUrlAreRejectedByRealMvcPermissionChain() throws Exception {
        String listOnly = login("/test-auth/login", "fixture-list-only");

        HttpResult allowedList = get("/endpoint/systems/page", listOnly);
        assertThat(allowedList.body().path("code").asInt()).isEqualTo(200);

        HttpResult wrongPermission = get("/endpoint/environments/page", listOnly);
        assertThat(wrongPermission.body().path("code").asInt()).isEqualTo(403);
        assertThat(wrongPermission.rawBody()).contains("没有访问权限");

        HttpResult directUrl = get("/endpoint/addresses/page", listOnly);
        assertThat(directUrl.body().path("code").asInt()).isEqualTo(403);
        assertThat(directUrl.rawBody()).contains("没有访问权限");
    }

    @Test
    void correctPermissionAndSessionEndpointsUseIssuedRealToken() throws Exception {
        String admin = login("/auth/login", "fixture-admin");

        HttpResult page = get("/endpoint/systems/page", admin);
        assertThat(page.body().path("code").asInt()).isEqualTo(200);
        assertThat(page.body().path("data").path("rows").isArray()).isTrue();

        HttpResult info = get("/system/user/getInfo", admin);
        assertThat(info.body().path("code").asInt()).isEqualTo(200);
        assertThat(info.body().path("data").path("user").path("userName").asString()).isEqualTo("fixture-admin");
        assertThat(info.body().path("data").path("permissions").toString()).contains("endpoint:system:list");

        HttpResult routers = get("/system/menu/getRouters", admin);
        assertThat(routers.body().path("code").asInt()).isEqualTo(200);
        assertThat(routers.body().path("data").get(0).path("path").asString()).isEqualTo("/endpoint");
    }

    @Test
    void fixtureAliasesWithoutHyphenKeepTheSamePermissions() throws Exception {
        String full = login("/test-auth/login", "fixtureadmin");
        String systemReader = login("/test-auth/login", "systemreader");
        String auditReader = login("/test-auth/login", "auditreader");
        String listOnly = login("/test-auth/login", "listonly");

        assertThat(get("/endpoint/systems/page", full).body().path("code").asInt()).isEqualTo(200);
        assertThat(get("/endpoint/systems/page", systemReader).body().path("code").asInt()).isEqualTo(200);
        assertThat(get("/endpoint/environments/page", systemReader).body().path("code").asInt()).isEqualTo(403);
        assertThat(get("/endpoint/audit/page?pageNum=1&pageSize=20", auditReader).body().path("code").asInt())
            .isEqualTo(200);
        assertThat(get("/endpoint/systems/page", auditReader).body().path("code").asInt()).isEqualTo(403);
        assertThat(get("/endpoint/systems/page", listOnly).body().path("code").asInt()).isEqualTo(200);
        assertThat(get("/endpoint/addresses/page", listOnly).body().path("code").asInt()).isEqualTo(403);
    }

    @Test
    void fixtureShellEndpointsAvoidUnknownExceptionToasts() throws Exception {
        String admin = login("/test-auth/login", "fixtureadmin");

        HttpResult box = get("/resource/message/box", admin);
        assertThat(box.body().path("code").asInt()).isEqualTo(200);
        assertThat(box.body().path("data").path("systemList").isArray()).isTrue();

        HttpResult closeSeconds = get("/system/config/configKey/sys.ui.messageCloseSeconds", admin);
        assertThat(closeSeconds.body().path("code").asInt()).isEqualTo(200);
        assertThat(closeSeconds.body().path("data").asInt()).isEqualTo(30);

        assertSseAvailable(admin);
    }

    @Test
    void systemDetailHidesActualAddressWithoutAddressQueryPermission() throws Exception {
        String admin = login("/test-auth/login", "fixture-admin");
        createEnvironment(admin, "SEC_ENV");
        createSystem(admin, "SEC_SYS");
        String addressId = createAddress(admin, "SEC_ENV", "SEC_REF", "192.0.2.77");
        replaceAddresses(admin, "SEC_SYS", List.of(addressId));

        String systemReader = login("/test-auth/login", "fixture-system-reader");
        HttpResult hidden = get("/endpoint/systems/SEC_SYS", systemReader);

        assertThat(hidden.body().path("code").asInt()).isEqualTo(200);
        assertThat(hidden.body().path("data").path("addresses").get(0).path("endpointRef").asString())
            .isEqualTo("SEC_REF");
        JsonNode host = hidden.body().path("data").path("addresses").get(0).path("host");
        assertThat(host.isMissingNode() || host.isNull()).isTrue();
        assertThat(hidden.rawBody()).doesNotContain("192.0.2.77");

        HttpResult visible = get("/endpoint/systems/SEC_SYS", admin);
        assertThat(visible.body().path("data").path("addresses").get(0).path("host").asString())
            .isEqualTo("192.0.2.77");
    }

    @Test
    void auditWithoutAddressQueryCannotReadStoredAddressSummary() throws Exception {
        String admin = login("/test-auth/login", "fixture-admin");
        createEnvironment(admin, "AUDIT_ENV");
        createAddress(admin, "AUDIT_ENV", "AUDIT_REF", "192.0.2.88");
        jdbcTemplate.update("INSERT INTO iws_endpoint_audit"
                + " (object_type, object_key, environment_code, action, before_summary, after_summary,"
                + " operator_name, command_key, request_hash, reason, catalog_revision, result_revision,"
                + " result_object_key, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            "ADDRESS", "1", "AUDIT_ENV", "UPDATE",
            "{\"host\":\"192.0.2.88\",\"port\":8443,\"basePath\":\"/secret\"}",
            "{\"host\":\"192.0.2.89\",\"port\":8443,\"basePath\":\"/secret-next\"}",
            "review", "legacy-address-audit", "a".repeat(64), "历史敏感审计脱敏", 1L, 1L, "1");

        String auditReader = login("/test-auth/login", "fixture-audit-reader");
        HttpResult audit = get("/endpoint/audit/page?pageNum=1&pageSize=20", auditReader);

        assertThat(audit.body().path("code").asInt()).isEqualTo(200);
        assertThat(audit.rawBody()).doesNotContain("192.0.2.88", "192.0.2.89", "/secret");
        assertThat(audit.body().path("data").path("rows").get(0).path("beforeSummary").asString())
            .contains("\"redacted\":true");
    }

    @Test
    void staleExpectedVersionIsRejectedThroughHttp() throws Exception {
        String admin = login("/test-auth/login", "fixture-admin");
        createEnvironment(admin, "VERSION_ENV");

        HttpResult first = put("/endpoint/environments/VERSION_ENV", admin, environmentUpdate(
            UUID.randomUUID().toString(), "1", "第一次修改"));
        assertThat(first.body().path("code").asInt()).isEqualTo(200);

        HttpResult stale = put("/endpoint/environments/VERSION_ENV", admin, environmentUpdate(
            UUID.randomUUID().toString(), "1", "陈旧版本修改"));
        assertThat(stale.body().path("code").asInt()).isEqualTo(500);
        assertThat(stale.body().path("msg").asString()).contains("版本冲突");
    }

    private String login(String path, String username) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", FIXTURE_PASSWORD);
        HttpResult result = post(path, null, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
        return result.body().path("data").path("access_token").asString();
    }

    private void createEnvironment(String token, String code) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentCode", code);
        body.put("displayName", code + "环境");
        body.put("description", "HTTP fixture");
        body.put("enabled", true);
        body.put("commandKey", UUID.randomUUID().toString());
        body.put("expectedVersion", "ABSENT");
        body.put("reason", "HTTP fixture 创建环境");
        HttpResult result = post("/endpoint/environments", token, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
    }

    private void createSystem(String token, String code) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemCode", code);
        body.put("displayName", code + "系统");
        body.put("description", "HTTP fixture");
        body.put("enabled", true);
        body.put("commandKey", UUID.randomUUID().toString());
        body.put("expectedVersion", "ABSENT");
        body.put("reason", "HTTP fixture 创建系统");
        HttpResult result = post("/endpoint/systems", token, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
    }

    private String createAddress(String token, String environmentCode, String endpointRef,
                                 String host) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentCode", environmentCode);
        body.put("endpointRef", endpointRef);
        body.put("protocol", "https");
        body.put("host", host);
        body.put("port", 8443);
        body.put("basePath", "/secret");
        body.put("credentialRef", "vault://fixture/endpoint");
        body.put("enabled", true);
        body.put("commandKey", UUID.randomUUID().toString());
        body.put("expectedVersion", "ABSENT");
        body.put("reason", "HTTP fixture 创建地址");
        HttpResult result = post("/endpoint/addresses", token, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
        return result.body().path("data").path("objectKey").asString();
    }

    private void replaceAddresses(String token, String systemCode, List<String> addressIds)
        throws IOException, InterruptedException {
        HttpResult detail = get("/endpoint/systems/" + systemCode, token);
        String revision = detail.body().path("data").path("revision").asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addressIds", addressIds);
        body.put("commandKey", UUID.randomUUID().toString());
        body.put("expectedVersion", revision);
        body.put("reason", "HTTP fixture 保存系统地址关联");
        HttpResult result = put("/endpoint/systems/" + systemCode + "/addresses", token, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
    }

    private Map<String, Object> environmentUpdate(String commandKey, String expectedVersion, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentCode", "VERSION_ENV");
        body.put("displayName", "版本冲突测试环境");
        body.put("description", "HTTP fixture");
        body.put("enabled", true);
        body.put("commandKey", commandKey);
        body.put("expectedVersion", expectedVersion);
        body.put("reason", reason);
        return body;
    }

    private HttpResult get(String path, String token) throws IOException, InterruptedException {
        return request("GET", path, token, null);
    }

    private void assertSseAvailable(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/resource/message"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + token)
            .header("clientid", CLIENT_ID)
            .GET()
            .build();
        HttpResponse<InputStream> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .get(5, TimeUnit.SECONDS);
        try (InputStream body = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/event-stream");
        }
    }

    private HttpResult post(String path, String token, Object body) throws IOException, InterruptedException {
        return request("POST", path, token, body);
    }

    private HttpResult put(String path, String token, Object body) throws IOException, InterruptedException {
        return request("PUT", path, token, body);
    }

    private HttpResult request(String method, String path, String token, Object body)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
            builder.header("clientid", CLIENT_ID);
        }
        String requestBody = body == null ? "" : jsonMapper.writeValueAsString(body);
        builder.method(method, HttpRequest.BodyPublishers.ofString(requestBody));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = response.body().isBlank() ? jsonMapper.createObjectNode() : jsonMapper.readTree(response.body());
        return new HttpResult(response.statusCode(), json, response.body());
    }

    /**
     * 实际 HTTP 响应。
     *
     * @param status HTTP 状态码
     * @param body 解析后的 JSON
     * @param rawBody 原始响应
     */
    private record HttpResult(int status, JsonNode body, String rawBody) {
    }
}
