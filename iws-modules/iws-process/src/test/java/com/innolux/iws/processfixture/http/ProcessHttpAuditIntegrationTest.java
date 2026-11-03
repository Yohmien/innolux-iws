/*
 * @Date         : 2026-10-23
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : 计划事件
 * @Version      : V1.0
 * @Description  : process 真实 HTTP、MVC、事务回滚与拒绝审计集成测试；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.processfixture.http;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * process 真实 HTTP 审计验收。
 *
 * <p>使用随机端口、真实 process Controller/Service/MyBatis 事务和 SaToken 拦截链；数据写入
 * 13307 隔离实例中固定的 {@code iws_process_http_test} 库。默认跳过，显式设置
 * {@code -Diws.process.test.enabled=true} 和 datadir 后才执行。</p>
 */
@Tag("dev")
@EnabledIf("fixtureEnabled")
@SpringBootTest(
    classes = ProcessHttpFixtureApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "server.address=127.0.0.1",
        "server.port=0",
        "spring.main.banner-mode=off",
        "spring.jmx.enabled=false",
        "springdoc.api-docs.enabled=false",
        "xss.enabled=false",
        "iws.endpoint.enabled=true",
        "iws.endpoint.current-environment=HTTP_ENV",
        "iws.process.enabled=true",
        "iws.process.scheduler.enabled=false",
        "iws.process.workflow.enabled=false",
        "sa-token.token-name=Authorization",
        "sa-token.is-concurrent=true",
        "sa-token.is-share=false",
        "sa-token.token-prefix=Bearer",
        "sa-token.jwt-secret-key=process-http-fixture-secret",
        "security.excludes=/auth/**,/test-auth/**,/error",
        "logging.level.root=warn"
    }
)
class ProcessHttpAuditIntegrationTest {

    private static final String CLIENT_ID = "0f7a0c5f3b2c4d6e8a1b9c7d5e3f2a10";
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
        return Boolean.parseBoolean(System.getProperty("iws.process.test.enabled", "false"));
    }

    @BeforeEach
    void resetFixture() throws SQLException {
        ProcessHttpFixtureDatabase.resetSchema();
        baseUrl = "http://127.0.0.1:" + port;
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        System.out.println("IWS_PROCESS_HTTP_FIXTURE_RUNNING_URL=" + baseUrl);
    }

    @AfterAll
    static void cleanFixture() throws SQLException {
        if (fixtureEnabled()) {
            ProcessHttpFixtureDatabase.dropSchema();
        }
    }

    @Test
    void wrongPermissionIsRejectedByRealMvcAndAudited() throws Exception {
        String listOnly = login("fixture-list-only");

        HttpResult allowed = get("/process/schedule/page?pageNum=1&pageSize=20", listOnly);
        assertThat(allowed.body().path("code").asInt()).as(allowed.rawBody()).isEqualTo(200);

        HttpResult denied = post("/process/schedule", listOnly, scheduleBody("PERM_DENIED", "权限拒绝用例"));
        assertThat(denied.body().path("code").asInt()).as(denied.rawBody()).isEqualTo(403);
        assertThat(denied.rawBody()).contains("没有访问权限");

        Map<String, Object> audit = latestAudit("/process/schedule");
        assertThat(audit.get("action_code")).isEqualTo("create");
        assertThat(audit.get("result")).isEqualTo("REJECTED");
        assertThat(audit.get("reason")).isEqualTo("REJECTED_DENIED");
        assertThat(String.valueOf(audit.get("actor_name"))).contains("9002");
        assertThat(count("SELECT COUNT(*) FROM iws_business_schedule WHERE business_code = ?", "PERM_DENIED"))
            .isZero();
    }

    @Test
    void parameterFailureIsRejectedAndAuditedWithoutBusinessRow() throws Exception {
        String admin = login("fixture-admin");

        HttpResult rejected = post("/process/schedule", admin,
            scheduleBody("", "参数失败用例"));
        assertThat(rejected.body().path("code").asInt()).as(rejected.rawBody()).isEqualTo(500);
        assertThat(rejected.rawBody()).contains("业务编码不能为空");

        Map<String, Object> audit = latestAudit("/process/schedule");
        assertThat(audit.get("action_code")).isEqualTo("create");
        assertThat(audit.get("result")).isEqualTo("REJECTED");
        assertThat(audit.get("reason")).isEqualTo("REJECTED_VALIDATION");
        assertThat(count("SELECT COUNT(*) FROM iws_business_schedule", new Object[0])).isZero();
    }

    @Test
    void businessRollbackKeepsRequiresNewRejectionAudit() throws Exception {
        String admin = login("fixture-admin");
        HttpResult created = post("/process/schedule", admin,
            scheduleBody("ROLLBACK_BIZ", "事务回滚用例"));
        assertThat(created.body().path("code").asInt()).as(created.rawBody()).isEqualTo(200);
        String scheduleId = created.body().path("data").path("id").asString();

        Map<String, Object> planType = new LinkedHashMap<>();
        planType.put("planType", "X".repeat(80));
        planType.put("planTypeName", "超长计划类型");
        planType.put("priority", 1);
        planType.put("enabled", true);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("planTypes", List.of(planType));
        request.put("expectedRevision", 1);
        request.put("commandKey", UUID.randomUUID().toString());
        request.put("reason", "触发数据库写入后回滚");

        HttpResult failed = put("/process/schedule/" + scheduleId + "/plan-types", admin, request);
        assertThat(failed.body().path("code").asInt()).as(failed.rawBody()).isEqualTo(500);

        assertThat(jdbcTemplate.queryForObject("SELECT revision FROM iws_business_schedule WHERE id = ?",
            Long.class, Long.valueOf(scheduleId))).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM iws_business_schedule_plan_type WHERE schedule_id = ?",
            Long.valueOf(scheduleId))).isZero();

        Map<String, Object> audit = latestAudit("/process/schedule/" + scheduleId + "/plan-types");
        assertThat(audit.get("action_code")).isEqualTo("savePlanTypes");
        assertThat(audit.get("result")).isEqualTo("REJECTED");
        assertThat(audit.get("reason")).isEqualTo("REJECTED_VALIDATION");
        assertThat(count("SELECT COUNT(*) FROM iws_process_action_audit"
            + " WHERE action_code = 'savePlanTypes' AND result = 'SUCCESS'", new Object[0])).isZero();
    }

    @Test
    void fixtureCapabilitiesExposeOnlyInternalLocalAction() throws Exception {
        String admin = login("fixture-admin");

        HttpResult options = get("/process/definition/options", admin);
        assertThat(options.body().path("code").asInt()).as(options.rawBody()).isEqualTo(200);
        assertThat(options.body().path("data").path("callContracts").size()).isZero();
        assertThat(options.rawBody()).contains("fixture-internal", "fixture-local", "fixture-state");
    }

    @Test
    void sessionAndMenusExposeProcessPagesAndHiddenInstanceDetail() throws Exception {
        String admin = login("fixture-admin");

        HttpResult info = get("/system/user/getInfo", admin);
        assertThat(info.body().path("code").asInt()).as(info.rawBody()).isEqualTo(200);
        assertThat(info.body().path("data").path("user").path("userName").asString())
            .isEqualTo("fixture-admin");

        HttpResult routers = get("/system/menu/getRouters", admin);
        assertThat(routers.body().path("code").asInt()).as(routers.rawBody()).isEqualTo(200);
        JsonNode root = routers.body().path("data").get(0);
        assertThat(root.path("path").asString()).isEqualTo("/process");
        assertThat(root.path("children").size()).isEqualTo(5);
        assertThat(root.path("children").get(3).path("hidden").asBoolean()).isTrue();
        assertThat(root.path("children").get(3).path("path").asString()).isEqualTo("instance/detail");
    }

    private Map<String, Object> scheduleBody(String businessCode, String businessName) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("businessCode", businessCode);
        schedule.put("businessName", businessName);
        schedule.put("enabled", true);
        schedule.put("fairnessWindowSize", 2);
        schedule.put("remark", "process HTTP fixture");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schedule", schedule);
        request.put("expectedRevision", null);
        request.put("commandKey", UUID.randomUUID().toString());
        request.put("reason", "process HTTP fixture 审计用例");
        return request;
    }

    private Map<String, Object> latestAudit(String path) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT action_code, result, reason, actor_name, entry_path, occurred_at"
                + " FROM iws_process_action_audit WHERE entry_path = ?"
                + " ORDER BY occurred_at DESC, id DESC LIMIT 1", path);
        assertThat(rows).as("未找到拒绝审计: " + path).isNotEmpty();
        return rows.getFirst();
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String login(String username) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", FIXTURE_PASSWORD);
        HttpResult result = post("/test-auth/login", null, body);
        assertThat(result.body().path("code").asInt()).as(result.rawBody()).isEqualTo(200);
        return result.body().path("data").path("access_token").asString();
    }

    private HttpResult get(String path, String token) throws IOException, InterruptedException {
        return request("GET", path, token, null);
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
            .timeout(Duration.ofSeconds(15))
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
