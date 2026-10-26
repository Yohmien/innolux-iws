/*
 * @Date         : 2026-10-26
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.redis;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.lock.DefaultLockKeyBuilder;
import com.baomidou.lock.LockInfo;
import com.baomidou.lock.LockTemplate;
import com.baomidou.lock.aop.LockInterceptor;
import com.baomidou.lock.executor.RedissonLockExecutor;
import com.baomidou.lock.spring.boot.autoconfigure.Lock4jProperties;
import com.innolux.iws.common.core.constant.CacheNames;
import com.innolux.iws.common.redis.config.RedisConfig;
import com.innolux.iws.common.redis.config.properties.RedissonProperties;
import com.innolux.iws.common.redis.handler.KeyPrefixHandler;
import com.innolux.iws.workflow.domain.bo.CompleteTaskBo;
import com.innolux.iws.workflow.service.impl.FlwTaskServiceImpl;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.NameMapper;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 应用命名空间隔离配置测试。
 *
 * @author YONGJIE.PENG
 */
@Tag("dev")
@Tag("prod")
@DisplayName("Redis 应用命名空间隔离")
public class RedisNamespaceIsolationTest {

    private static final String DEFAULT_PREFIX = "innolux-iws";
    private static final String PREFIX_ENV_NAME = "IWS_REDIS_KEY_PREFIX";

    /**
     * 验证开发与生产环境都显式配置相同的默认应用前缀。
     *
     * @param profile 环境名称
     * @throws IOException 读取配置失败时抛出
     */
    @ParameterizedTest(name = "{0} 环境绑定默认应用前缀")
    @ValueSource(strings = {"dev", "prod"})
    void profileBindsDefaultApplicationPrefix(String profile) throws IOException {
        RedissonProperties properties = bindProfile(profile, null);

        assertEquals(DEFAULT_PREFIX, properties.getKeyPrefix());
    }

    /**
     * 验证部署环境变量可同时覆盖两个环境的应用前缀。
     *
     * @param profile 环境名称
     * @throws IOException 读取配置失败时抛出
     */
    @ParameterizedTest(name = "{0} 环境绑定外部应用前缀")
    @ValueSource(strings = {"dev", "prod"})
    void profileBindsApplicationPrefixOverride(String profile) throws IOException {
        RedissonProperties properties = bindProfile(profile, "iws-warehouse");

        assertEquals("iws-warehouse", properties.getKeyPrefix());
    }

    /**
     * 验证生产定制器将字典缓存名称映射到应用命名空间。
     *
     * @param profile 环境名称
     * @throws IOException 读取配置失败时抛出
     */
    @ParameterizedTest(name = "{0} 环境通过 NameMapper 隔离字典缓存")
    @ValueSource(strings = {"dev", "prod"})
    void configuredNameMapperIsolatesDictCache(String profile) throws IOException {
        NameMapper nameMapper = createNameMapper(bindProfile(profile, null));
        String physicalKey = nameMapper.map(CacheNames.SYS_DICT);

        assertEquals(DEFAULT_PREFIX + ":" + CacheNames.SYS_DICT, physicalKey);
        assertNotEquals(CacheNames.SYS_DICT, physicalKey);
        assertEquals(CacheNames.SYS_DICT, nameMapper.unmap(physicalKey));
    }

    /**
     * 验证 Sa-Token 实际生成的 Token 与 Session key 被映射到应用命名空间。
     *
     * @param profile 环境名称
     * @throws IOException 读取配置失败时抛出
     */
    @ParameterizedTest(name = "{0} 环境通过 NameMapper 隔离 Sa-Token key")
    @ValueSource(strings = {"dev", "prod"})
    void configuredNameMapperIsolatesSaTokenKeys(String profile) throws IOException {
        StandardEnvironment environment = loadEnvironment(profile);
        SaTokenConfig saTokenConfig = bindProperty(environment, "sa-token", SaTokenConfig.class);
        StpLogic stpLogic = new StpLogic("login").setConfig(saTokenConfig);
        NameMapper nameMapper = createNameMapper(bindProperty(environment, "redisson", RedissonProperties.class));

        String tokenKey = stpLogic.splicingKeyTokenValue("test-token");
        String sessionKey = stpLogic.splicingKeySession(1001L);

        assertEquals("Authorization:login:token:test-token", tokenKey);
        assertEquals(DEFAULT_PREFIX + ":" + tokenKey, nameMapper.map(tokenKey));
        assertEquals(DEFAULT_PREFIX + ":" + sessionKey, nameMapper.map(sessionKey));
    }

    /**
     * 验证真实 Lock4j 注解键进入 Redisson 锁后由 NameMapper 映射到应用命名空间。
     *
     * @param profile 环境名称
     * @throws Throwable 构建锁调用链失败时抛出
     */
    @ParameterizedTest(name = "{0} 环境通过 NameMapper 隔离 Lock4j key")
    @ValueSource(strings = {"dev", "prod"})
    void configuredNameMapperIsolatesLock4jKeys(String profile) throws Throwable {
        StandardEnvironment environment = loadEnvironment(profile);
        NameMapper nameMapper = createNameMapper(bindProperty(environment, "redisson", RedissonProperties.class));
        String lockKey = buildLock4jKey(environment);

        assertEquals("lock4j:" + FlwTaskServiceImpl.class.getName() + "completeTask#9001", lockKey);
        assertTrue(lockKey.startsWith("lock4j:"));
        assertEquals(DEFAULT_PREFIX + ":" + lockKey, nameMapper.map(lockKey));

        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock rLock = mock(RLock.class);
        when(redissonClient.getLock(lockKey)).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        assertNotNull(new RedissonLockExecutor(redissonClient).acquire(lockKey, "lock-value", 30000L, 3000L));
        verify(redissonClient).getLock(lockKey);
    }

    private RedissonProperties bindProfile(String profile, String prefixOverride) throws IOException {
        StandardEnvironment environment = loadEnvironment(profile);
        if (prefixOverride != null) {
            environment.getPropertySources().addFirst(new MapPropertySource(
                "redis-prefix-override", Map.of(PREFIX_ENV_NAME, prefixOverride)));
        }
        return bindProperty(environment, "redisson", RedissonProperties.class);
    }

    private StandardEnvironment loadEnvironment(String profile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        loadYaml(environment, "application.yml");
        loadYaml(environment, "application-" + profile + ".yml");
        return environment;
    }

    private void loadYaml(StandardEnvironment environment, String resourceName) throws IOException {
        for (PropertySource<?> propertySource : new YamlPropertySourceLoader()
            .load(resourceName, new ClassPathResource(resourceName))) {
            environment.getPropertySources().addLast(propertySource);
        }
    }

    private <T> T bindProperty(StandardEnvironment environment, String prefix, Class<T> type) {
        return Binder.get(environment).bind(prefix, Bindable.of(type)).get();
    }

    private String buildLock4jKey(StandardEnvironment environment) throws Throwable {
        LockTemplate lockTemplate = mock(LockTemplate.class);
        when(lockTemplate.lock(anyString(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            String lockKey = invocation.getArgument(0);
            return new LockInfo(lockKey, "lock-value", 30000L, 3000L, 1, null, null);
        });
        when(lockTemplate.releaseLock(any(LockInfo.class))).thenReturn(true);
        LockInterceptor interceptor = new LockInterceptor(
            lockTemplate,
            List.of(new DefaultLockKeyBuilder(new DefaultListableBeanFactory())),
            List.of((lockKey, method, args) -> { }),
            bindProperty(environment, "lock4j", Lock4jProperties.class));
        interceptor.afterPropertiesSet();

        CompleteTaskBo completeTaskBo = new CompleteTaskBo();
        completeTaskBo.setTaskId(9001L);
        Method method = FlwTaskServiceImpl.class.getMethod("completeTask", CompleteTaskBo.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getThis()).thenReturn(mock(FlwTaskServiceImpl.class));
        when(invocation.getMethod()).thenReturn(method);
        when(invocation.getArguments()).thenReturn(new Object[]{completeTaskBo});

        interceptor.invoke(invocation);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(lockTemplate).lock(keyCaptor.capture(), anyLong(), anyLong(), any());
        return keyCaptor.getValue();
    }

    private NameMapper createNameMapper(RedissonProperties properties) {
        RedisConfig redisConfig = new RedisConfig();
        ReflectionTestUtils.setField(redisConfig, "redissonProperties", properties);
        Config redissonConfig = new Config();

        redisConfig.redissonCustomizer().customize(redissonConfig);

        return assertInstanceOf(KeyPrefixHandler.class, redissonConfig.getNameMapper());
    }

}
