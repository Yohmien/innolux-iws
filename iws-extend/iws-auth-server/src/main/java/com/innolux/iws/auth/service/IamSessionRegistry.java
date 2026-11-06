/*
 * @Date         : 2026-11-03
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 统一身份认证
 * @Version      : V1.5
 * @Description  : 中心根会话、共享授权指纹与跨设备隔离的应用会话原子登记；
 * @ModifyRecord : V1.0 新建；V1.1 增加应用类型互斥、代次与幂等激活；
 *                  V1.2 根会话绑定凭据指纹，状态复核统一识别密码变更；
 *                  V1.3 根会话绑定完整授权指纹，覆盖角色数据范围与部门范围；
 *                  V1.4 共享指纹排除应用接入权限，改由应用状态接口逐应用校验；
 *                  V1.5 应用会话撤销改为载荷 CAS，避免旧根退出删除并发提交的新会话；
 *                  V1.6 应用会话按根会话隔离，允许同一账号在不同设备同时登录；
 */

package com.innolux.iws.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.innolux.iws.auth.config.IamProperties;
import com.innolux.iws.common.auth.constant.IamProtocolConstants;
import com.innolux.iws.common.auth.domain.IamApplicationSession;
import com.innolux.iws.common.auth.domain.IamChildSession;
import com.innolux.iws.common.auth.domain.IamRootSession;
import com.innolux.iws.common.auth.domain.IamSessionStatusResponse;
import com.innolux.iws.common.auth.domain.IamTicketCommitResult;
import com.innolux.iws.common.auth.domain.IamTicketExchangeResponse;
import com.innolux.iws.common.auth.utils.IamCredentialFingerprint;
import com.innolux.iws.common.auth.utils.IamSecureTokens;
import com.innolux.iws.common.core.constant.SystemConstants;
import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.common.json.utils.JsonUtils;
import com.innolux.iws.common.redis.utils.RedisUtils;
import com.innolux.iws.system.api.model.LoginUser;
import com.innolux.iws.system.domain.vo.SysUserVo;
import com.innolux.iws.system.service.IamAuthorizationSnapshotService;
import com.innolux.iws.system.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 中心根会话与应用会话登记表。
 */
@Service
@RequiredArgsConstructor
public class IamSessionRegistry {

    private static final String COMMIT_APPLICATION_SCRIPT = """
        local preparation = redis.call('GET', KEYS[1])
        if not preparation or preparation ~= ARGV[1] then return 0 end
        local ticket = redis.call('GET', KEYS[2])
        if not ticket or ticket ~= ARGV[2] then return 0 end
        local active = redis.call('GET', KEYS[3]) or ''
        if active ~= ARGV[3] then return 0 end
        redis.call('SET', KEYS[3], ARGV[4], 'PX', ARGV[7])
        redis.call('SET', KEYS[4], ARGV[4], 'PX', ARGV[7])
        redis.call('SET', KEYS[5], ARGV[5], 'PX', ARGV[8])
        redis.call('DEL', KEYS[1], KEYS[2])
        redis.call('SADD', KEYS[6], ARGV[6])
        redis.call('PEXPIRE', KEYS[6], ARGV[9])
        return 1
        """;

    private final IamProperties properties;
    private final ISysUserService userService;
    private final IamAuthorizationSnapshotService authorizationSnapshotService;

    /**
     * 为刚完成的中心登录创建根会话，并绑定到中心 Token Session。
     *
     * @param loginUser 原系统登录用户
     * @return 根会话标识
     */
    public String createRootSession(LoginUser loginUser) {
        long now = Instant.now().toEpochMilli();
        Duration ttl = properties.getRootSessionTtl();
        String rootSid = IamSecureTokens.randomToken();
        SysUserVo user = userService.selectUserById(loginUser.getUserId());
        if (user == null) {
            throw new IllegalStateException("登录账号不存在，无法建立统一身份根会话");
        }
        String credentialFingerprint = IamCredentialFingerprint.of(user.getPassword());
        String authorizationFingerprint = sharedAuthorizationFingerprint(user);
        IamRootSession rootSession = new IamRootSession(
            IamProtocolConstants.ISSUER,
            rootSid,
            loginUser.getUserId(),
            loginUser.getUsername(),
            credentialFingerprint,
            authorizationFingerprint,
            now,
            now + ttl.toMillis()
        );
        RedisUtils.setCacheObject(
            IamProtocolConstants.ROOT_SESSION_KEY_PREFIX + rootSid,
            JsonUtils.toJsonString(rootSession),
            ttl
        );
        StpUtil.getTokenSession().set(IamProtocolConstants.ROOT_SID_SESSION_KEY, rootSid);
        StpUtil.getTokenSession().set(
            IamProtocolConstants.CREDENTIAL_FINGERPRINT_KEY,
            credentialFingerprint
        );
        return rootSid;
    }

    /**
     * 在身份与准入校验完成后原子激活应用会话。
     *
     * <p>互斥键为 issuer、userId、applicationType、rootSid。同一根会话、应用和终端
     * 重复进入返回现有代次；不同设备使用不同根会话，可以同时保持登录。</p>
     *
     * @param loginUser     当前原系统登录用户
     * @param applicationId 目标应用标识
     * @param application   目标应用配置
     * @param frontendId    受信前端入口
     * @param terminal      终端类型
     * @param transactionId 幂等登录事务标识
     * @param rootSid       中心根会话标识
     * @return 激活结果及必要的回滚信息
     */
    public ActivationResult activateApplication(
        LoginUser loginUser,
        String applicationId,
        IamProperties.Application application,
        String frontendId,
        String terminal,
        String transactionId,
        String rootSid
    ) {
        String applicationType = application.getType();
        String scope = sessionScope(loginUser.getUserId(), applicationType, rootSid);
        String lockKey = IamProtocolConstants.APPLICATION_SESSION_LOCK_PREFIX + scope;
        RLock lock = RedisUtils.getClient().getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("应用会话激活繁忙，请重试");
            }
            String transactionKey = IamProtocolConstants.ACTIVATION_TRANSACTION_KEY_PREFIX
                + scope + ":" + frontendId + ":" + transactionId;
            RBucket<String> transactionBucket = RedisUtils.getClient().getBucket(transactionKey);
            IamApplicationSession completed = readSession(transactionBucket.get());
            if (completed != null
                && loginUser.getUserId().equals(completed.userId())
                && applicationId.equals(completed.applicationId())
                && frontendId.equals(completed.frontendId())
                && terminal.equals(completed.terminal())
                && rootSid.equals(completed.rootSid())) {
                return ActivationResult.reused(completed);
            }

            String activeKey = activeSessionKey(scope);
            RBucket<String> activeBucket = RedisUtils.getClient().getBucket(activeKey);
            String previousPayload = activeBucket.get();
            IamApplicationSession current = readSession(previousPayload);
            long now = Instant.now().toEpochMilli();
            if (isSameSession(current, applicationId, frontendId, terminal, rootSid, now)) {
                transactionBucket.set(JsonUtils.toJsonString(current), properties.getRootSessionTtl());
                return ActivationResult.reused(current);
            }

            long generation = nextGeneration(scope);
            long expiresAt = now + properties.getRootSessionTtl().toMillis();
            IamApplicationSession activated = new IamApplicationSession(
                IamProtocolConstants.ISSUER,
                applicationId,
                applicationType,
                frontendId,
                loginUser.getUserId(),
                rootSid,
                IamSecureTokens.randomToken(),
                generation,
                terminal,
                transactionId,
                now,
                expiresAt
            );
            String payload = JsonUtils.toJsonString(activated);
            activeBucket.set(payload, properties.getRootSessionTtl());
            transactionBucket.set(payload, properties.getRootSessionTtl());
            registerChildSession(activated);
            return ActivationResult.activated(
                activated,
                activeKey,
                transactionKey,
                previousPayload,
                payload
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("应用会话激活被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 为目标应用准备一个尚未提交的会话申请。
     *
     * <p>此阶段只分配会话标识和代次，不修改当前有效占位。目标应用完成本地
     * 账号、资源和会话准备后再携带票据提交；同一事务重复请求复用同一申请。</p>
     *
     * @param loginUser     中心当前账号
     * @param applicationId 目标应用
     * @param application   应用配置
     * @param frontendId    受信前端入口
     * @param terminal      终端类型
     * @param transactionId 幂等登录事务标识
     * @param rootSid       根会话标识
     * @return 待提交申请
     */
    public IamApplicationPreparation prepareApplication(
        LoginUser loginUser,
        String applicationId,
        IamProperties.Application application,
        String frontendId,
        String terminal,
        String transactionId,
        String rootSid
    ) {
        String scope = sessionScope(loginUser.getUserId(), application.getType(), rootSid);
        RLock lock = RedisUtils.getClient().getLock(
            IamProtocolConstants.APPLICATION_SESSION_LOCK_PREFIX + scope
        );
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("应用会话准备繁忙，请重试");
            }
            String transactionKey = IamProtocolConstants.ACTIVATION_TRANSACTION_KEY_PREFIX
                + scope + ":" + frontendId + ":" + transactionId;
            IamApplicationSession completed = readSession(
                RedisUtils.getClient().<String>getBucket(transactionKey).get()
            );
            if (completed != null) {
                throw new ServiceException(
                    "登录事务已提交，不能重新准备或分配新的应用会话代次",
                    409
                );
            }
            String key = preparationKey(scope, frontendId, transactionId);
            RBucket<String> bucket = RedisUtils.getClient().getBucket(key);
            IamApplicationPreparation existing = readPreparation(bucket.get());
            long now = Instant.now().toEpochMilli();
            if (existing != null
                && existing.expiresAt() > now
                && loginUser.getUserId().equals(existing.userId())
                && applicationId.equals(existing.applicationId())
                && application.getType().equals(existing.applicationType())
                && frontendId.equals(existing.frontendId())
                && terminal.equals(existing.terminal())
                && rootSid.equals(existing.rootSid())) {
                return existing;
            }
            long generation = nextGeneration(scope);
            IamApplicationPreparation preparation = new IamApplicationPreparation(
                IamProtocolConstants.ISSUER,
                applicationId,
                application.getType(),
                frontendId,
                loginUser.getUserId(),
                loginUser.getUsername(),
                rootSid,
                IamSecureTokens.randomToken(),
                generation,
                terminal,
                transactionId,
                now,
                now + properties.getRootSessionTtl().toMillis()
            );
            bucket.set(JsonUtils.toJsonString(preparation), properties.getRootSessionTtl());
            return preparation;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("应用会话准备被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 将目标应用已准备好的申请提交为当前有效占位。
     *
     * <p>提交时检查根会话、申请有效期和同主体同应用类型的代次。较旧的申请
     * 不能覆盖已经提交的较新会话；同一申请重复提交返回原会话。</p>
     *
     * @param preparation 待提交申请
     * @param rootSid     根会话标识
     * @return 已提交会话
     */
    public ActivationResult commitApplication(
        IamApplicationPreparation preparation,
        String rootSid,
        String ticket,
        String ticketPayload,
        String state,
        String commitId,
        IamTicketExchangeResponse identity
    ) {
        if (preparation == null || preparation.userId() == null
            || !IamProtocolConstants.ISSUER.equals(preparation.issuer())
            || !rootSid.equals(preparation.rootSid())) {
            throw new IllegalStateException("应用会话申请不完整");
        }
        String scope = sessionScope(
            preparation.userId(),
            preparation.applicationType(),
            preparation.rootSid()
        );
        RLock lock = RedisUtils.getClient().getLock(
            IamProtocolConstants.APPLICATION_SESSION_LOCK_PREFIX + scope
        );
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("应用会话提交繁忙，请重试");
            }
            IamSessionStatusResponse root = rootStatus(rootSid);
            if (!root.active() || !preparation.userId().equals(root.userId())) {
                throw new IllegalStateException("统一身份会话已失效");
            }
            String preparationKey = preparationKey(
                scope,
                preparation.frontendId(),
                preparation.transactionId()
            );
            RBucket<String> preparationBucket =
                RedisUtils.getClient().getBucket(preparationKey);
            String preparationValue = preparationBucket.get();
            IamApplicationPreparation currentPreparation = readPreparation(preparationValue);
            String preparationPayload = RedisUtils.getClient()
                .<String>getBucket(preparationKey, StringCodec.INSTANCE)
                .get();
            if (!samePreparation(preparation, currentPreparation)
                || currentPreparation.expiresAt() <= Instant.now().toEpochMilli()) {
                throw new IllegalStateException("应用会话申请已过期或已被使用");
            }

            String activeKey = activeSessionKey(scope);
            RBucket<String> activeBucket = RedisUtils.getClient().getBucket(activeKey);
            String previousPayload = activeBucket.get();
            String previousPayloadRaw = RedisUtils.getClient()
                .<String>getBucket(activeKey, StringCodec.INSTANCE)
                .get();
            IamApplicationSession active = readSession(previousPayload);
            long now = Instant.now().toEpochMilli();
            if (active != null && active.expiresAt() > now) {
                if (matches(
                    active,
                    preparation.applicationId(),
                    preparation.frontendId(),
                    rootSid,
                    preparation.sessionId(),
                    preparation.sessionGeneration(),
                    preparation.terminal()
                )) {
                    return ActivationResult.reused(active);
                }
                if (active.sessionGeneration() >= preparation.sessionGeneration()) {
                    throw new ServiceException("应用会话已被较新的登录替换", 409);
                }
            }

            long expiresAt = Math.min(
                currentPreparation.expiresAt(),
                root.expiresAt()
            );
            IamApplicationSession activated = new IamApplicationSession(
                IamProtocolConstants.ISSUER,
                preparation.applicationId(),
                preparation.applicationType(),
                preparation.frontendId(),
                preparation.userId(),
                rootSid,
                preparation.sessionId(),
                preparation.sessionGeneration(),
                preparation.terminal(),
                preparation.transactionId(),
                now,
                expiresAt
            );
            String payload = JsonUtils.toJsonString(activated);
            String transactionKey = IamProtocolConstants.ACTIVATION_TRANSACTION_KEY_PREFIX
                + scope + ":" + preparation.frontendId() + ":" + preparation.transactionId();
            String commitResultKey = IamProtocolConstants.TICKET_COMMIT_KEY_PREFIX + ticket;
            String childKey = IamProtocolConstants.ROOT_CHILDREN_KEY_PREFIX + rootSid;
            String commitResultPayload = JsonUtils.toJsonString(
                new IamTicketCommitResult(state, commitId, identity)
            );
            String encodedActivePayload = JsonUtils.toJsonString(payload);
            String encodedCommitResultPayload = JsonUtils.toJsonString(commitResultPayload);
            String encodedChildPayload = JsonUtils.toJsonString(childPayload(activated));
            long activeTtl = Math.max(1, expiresAt - now);
            Long committed = RedisUtils.getClient().getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                COMMIT_APPLICATION_SCRIPT,
                RScript.ReturnType.LONG,
                java.util.List.of(
                    preparationKey,
                    IamProtocolConstants.TICKET_KEY_PREFIX + ticket,
                    activeKey,
                    transactionKey,
                    commitResultKey,
                    childKey
                ),
                preparationPayload,
                ticketPayload,
                previousPayloadRaw == null ? "" : previousPayloadRaw,
                encodedActivePayload,
                encodedCommitResultPayload,
                encodedChildPayload,
                Long.toString(activeTtl),
                Long.toString(properties.getTicketTtl().toMillis()),
                Long.toString(properties.getRootSessionTtl().toMillis())
            );
            if (committed == null || committed != 1L) {
                throw new ServiceException("应用会话申请已被使用或当前占位已变化", 409);
            }
            return ActivationResult.activated(
                activated,
                activeKey,
                preparationKey,
                previousPayload,
                payload
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("应用会话提交被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 仅在当前占位仍是本次新激活时恢复前一占位。
     *
     * @param activation 激活结果
     */
    public void rollbackActivation(ActivationResult activation) {
        if (activation == null || !activation.newlyActivated() || activation.session() == null) {
            return;
        }
        IamApplicationSession session = activation.session();
        String scope = sessionScope(
            session.userId(),
            session.applicationType(),
            session.rootSid()
        );
        RLock lock = RedisUtils.getClient().getLock(
            IamProtocolConstants.APPLICATION_SESSION_LOCK_PREFIX + scope
        );
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            RBucket<String> activeBucket = RedisUtils.getClient().getBucket(activation.activeKey());
            if (activeBucket.compareAndSet(activation.activatedPayload(), activation.previousPayload())) {
                if (activation.previousPayload() == null) {
                    activeBucket.delete();
                } else {
                    IamApplicationSession previous = readSession(activation.previousPayload());
                    long remaining = previous == null
                        ? 0
                        : previous.expiresAt() - Instant.now().toEpochMilli();
                    if (remaining <= 0) {
                        activeBucket.delete();
                    } else {
                        activeBucket.set(activation.previousPayload(), Duration.ofMillis(remaining));
                    }
                }
            }
            RBucket<String> transactionBucket = RedisUtils.getClient().getBucket(activation.transactionKey());
            transactionBucket.compareAndSet(activation.activatedPayload(), null);
            removeChildSession(session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 获取当前中心 Token 绑定的根会话标识。
     *
     * @return 根会话标识；不存在时返回 null
     */
    public String currentRootSid() {
        Object value = StpUtil.getTokenSession().get(IamProtocolConstants.ROOT_SID_SESSION_KEY);
        return value == null ? null : value.toString();
    }

    /**
     * 查询根会话状态。
     *
     * @param rootSid 根会话标识
     * @return 根会话状态
     */
    public IamSessionStatusResponse status(String rootSid) {
        IamSessionStatusResponse root = rootStatus(rootSid);
        return root;
    }

    /**
     * 同时校验根会话和指定应用会话占位。
     *
     * @param rootSid           根会话标识
     * @param applicationId     目标应用标识
     * @param frontendId        受信前端入口
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
     * @return 状态；应用占位不匹配时 active=false
     */
    public IamSessionStatusResponse status(
        String rootSid,
        String applicationId,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        IamSessionStatusResponse root = rootStatus(rootSid);
        if (!root.active()) {
            return root;
        }
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (application == null) {
            return inactive(root, "APPLICATION_NOT_REGISTERED");
        }
        String scope = sessionScope(root.userId(), application.getType(), rootSid);
        IamApplicationSession active = readSession(
            RedisUtils.getClient().<String>getBucket(activeSessionKey(scope)).get()
        );
        if (active == null || active.expiresAt() <= Instant.now().toEpochMilli()) {
            return inactive(root, "APPLICATION_SESSION_EXPIRED");
        }
        if (!applicationId.equals(active.applicationId())
            || !frontendId.equals(active.frontendId())
            || !rootSid.equals(active.rootSid())
            || !sessionId.equals(active.sessionId())
            || sessionGeneration != active.sessionGeneration()
            || !terminal.equals(active.terminal())) {
            return inactive(root, "APPLICATION_SESSION_REPLACED");
        }
        return new IamSessionStatusResponse(
            true,
            IamProtocolConstants.ISSUER,
            root.userId(),
            root.username(),
            Math.min(root.expiresAt(), active.expiresAt()),
            null
        );
    }

    /**
     * 撤销中心当前根会话。
     */
    public void revokeCurrentRootSession() {
        revoke(currentRootSid());
    }

    /**
     * 撤销指定根会话及其仍匹配该根会话的应用占位。
     *
     * @param rootSid 根会话标识
     */
    public void revoke(String rootSid) {
        if (rootSid == null || rootSid.isBlank()) {
            return;
        }
        String childrenKey = IamProtocolConstants.ROOT_CHILDREN_KEY_PREFIX + rootSid;
        RSet<String> children = RedisUtils.getClient().getSet(childrenKey);
        for (String payload : children) {
            IamChildSession child = JsonUtils.parseObject(payload, IamChildSession.class);
            if (child != null && rootSid.equals(child.rootSid())) {
                revokeActiveIfMatches(
                    child.userId(),
                    child.applicationType(),
                    child.rootSid(),
                    child.applicationId(),
                    child.frontendId(),
                    child.sessionId(),
                    child.sessionGeneration(),
                    child.terminal()
                );
            }
        }
        RedisUtils.deleteObject(IamProtocolConstants.ROOT_SESSION_KEY_PREFIX + rootSid);
        RedisUtils.deleteObject(childrenKey);
    }

    /**
     * 仅在请求仍指向当前占位时撤销单个应用会话。
     *
     * @param rootSid           根会话标识
     * @param applicationId     目标应用标识
     * @param frontendId        受信前端入口
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
     * @return 是否撤销了当前占位
     */
    public boolean revokeApplication(
        String rootSid,
        String applicationId,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        IamSessionStatusResponse root = rootStatus(rootSid);
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (!root.active() || application == null) {
            return false;
        }
        return revokeActiveIfMatches(
            root.userId(),
            application.getType(),
            rootSid,
            applicationId,
            frontendId,
            sessionId,
            sessionGeneration,
            terminal
        );
    }

    /**
     * 仅当请求携带的应用会话仍是当前占位时，原子撤销整个根会话。
     *
     * <p>旧应用会话被新登录替换后，不能再通过旧退出请求删除新会话。
     * 这里复用应用类型激活锁，保证检查占位和撤销根会话之间不会被同类型
     * 新登录插入。</p>
     *
     * @param rootSid           根会话标识
     * @param applicationId     目标应用标识
     * @param frontendId        受信前端入口
     * @param sessionId         应用会话标识
     * @param sessionGeneration 应用会话代次
     * @param terminal          终端类型
     * @return 是否确认撤销根会话
     */
    public boolean revokeRootIfCurrent(
        String rootSid,
        String applicationId,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        IamSessionStatusResponse root = rootStatus(rootSid);
        IamProperties.Application application = properties.getApplications().get(applicationId);
        if (!root.active() || application == null || root.userId() == null) {
            return false;
        }
        String scope = sessionScope(root.userId(), application.getType(), rootSid);
        RLock lock = RedisUtils.getClient().getLock(
            IamProtocolConstants.APPLICATION_SESSION_LOCK_PREFIX + scope
        );
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("应用会话撤销锁等待超时");
            }
            if (!rootStatus(rootSid).active()) {
                return false;
            }
            IamApplicationSession active = readSession(
                RedisUtils.getClient().<String>getBucket(activeSessionKey(scope)).get()
            );
            if (!matches(
                active,
                applicationId,
                frontendId,
                rootSid,
                sessionId,
                sessionGeneration,
                terminal
            )) {
                return false;
            }
            revoke(rootSid);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("应用会话撤销被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 登记应用会话关联，供根会话统一退出使用。
     *
     * @param session 已激活应用会话
     */
    private void registerChildSession(IamApplicationSession session) {
        String key = IamProtocolConstants.ROOT_CHILDREN_KEY_PREFIX + session.rootSid();
        RSet<String> children = RedisUtils.getClient().getSet(key);
        children.add(childPayload(session));
        children.expire(properties.getRootSessionTtl());
    }

    /**
     * 移除本次新激活且未成功签票的会话关联。
     *
     * @param session 待移除会话
     */
    private void removeChildSession(IamApplicationSession session) {
        RSet<String> children = RedisUtils.getClient().getSet(
            IamProtocolConstants.ROOT_CHILDREN_KEY_PREFIX + session.rootSid()
        );
        children.remove(childPayload(session));
    }

    /**
     * 构造根会话下的应用子会话记录。
     *
     * @param session 应用会话
     * @return JSON 载荷
     */
    private String childPayload(IamApplicationSession session) {
        IamChildSession child = new IamChildSession(
            session.applicationId(),
            session.applicationType(),
            session.frontendId(),
            session.userId(),
            null,
            session.rootSid(),
            session.sessionId(),
            session.sessionGeneration(),
            session.terminal(),
            session.expiresAt(),
            session.activatedAt()
        );
        return JsonUtils.toJsonString(child);
    }

    /**
     * 根会话状态。
     *
     * @param rootSid 根会话标识
     * @return 根会话状态
     */
    private IamSessionStatusResponse rootStatus(String rootSid) {
        if (rootSid == null || rootSid.isBlank()) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, null, null, 0, "EMPTY_ROOT_SESSION"
            );
        }
        String payload = RedisUtils.getCacheObject(IamProtocolConstants.ROOT_SESSION_KEY_PREFIX + rootSid);
        if (payload == null) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, null, null, 0, "ROOT_SESSION_EXPIRED_OR_REVOKED"
            );
        }
        IamRootSession rootSession = JsonUtils.parseObject(payload, IamRootSession.class);
        if (rootSession == null || !IamProtocolConstants.ISSUER.equals(rootSession.issuer())
            || rootSession.expiresAt() <= Instant.now().toEpochMilli()) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, null, null, 0, "ROOT_SESSION_EXPIRED_OR_REVOKED"
            );
        }
        SysUserVo user = userService.selectUserById(rootSession.userId());
        if (user == null || SystemConstants.DISABLE.equals(user.getStatus())) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, rootSession.userId(),
                user == null ? null : user.getUserName(), rootSession.expiresAt(),
                "USER_DISABLED_OR_MISSING"
            );
        }
        if (!IamCredentialFingerprint.matches(
            user.getPassword(), rootSession.credentialFingerprint()
        )) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, rootSession.userId(), user.getUserName(),
                rootSession.expiresAt(),
                "CREDENTIAL_CHANGED"
            );
        }
        String authorizationFingerprint = sharedAuthorizationFingerprint(user);
        if (!authorizationFingerprint.equals(rootSession.authorizationFingerprint())) {
            return new IamSessionStatusResponse(
                false, IamProtocolConstants.ISSUER, rootSession.userId(), user.getUserName(),
                rootSession.expiresAt(),
                "AUTHORIZATION_CHANGED"
            );
        }
        return new IamSessionStatusResponse(
            true, IamProtocolConstants.ISSUER, rootSession.userId(), user.getUserName(),
            rootSession.expiresAt(), null
        );
    }

    /**
     * 计算不包含应用接入资格的共享授权指纹；接入资格在应用状态接口中按应用校验。
     *
     * @param user 当前账号
     * @return 共享授权指纹
     */
    private String sharedAuthorizationFingerprint(SysUserVo user) {
        String[] accessPermissions = properties.getApplications().values().stream()
            .map(IamProperties.Application::getAccessPermission)
            .filter(permission -> permission != null && !permission.isBlank())
            .distinct()
            .toArray(String[]::new);
        return authorizationSnapshotService.snapshot(user, accessPermissions).fingerprint();
    }

    /**
     * 构造保留权威主体的失效状态。
     *
     * @param root   根会话状态
     * @param reason 失效原因
     * @return 应用会话失效状态
     */
    private IamSessionStatusResponse inactive(IamSessionStatusResponse root, String reason) {
        return new IamSessionStatusResponse(
            false, IamProtocolConstants.ISSUER, root.userId(), root.username(),
            root.expiresAt(), reason
        );
    }

    /**
     * 从 JSON 读取应用会话。
     *
     * @param payload JSON 文本
     * @return 应用会话；无法解析时返回 null
     */
    private IamApplicationSession readSession(String payload) {
        return payload == null ? null : JsonUtils.parseObject(payload, IamApplicationSession.class);
    }

    private IamApplicationPreparation readPreparation(String payload) {
        return payload == null
            ? null
            : JsonUtils.parseObject(payload, IamApplicationPreparation.class);
    }

    private boolean samePreparation(
        IamApplicationPreparation expected,
        IamApplicationPreparation actual
    ) {
        return actual != null
            && expected.issuer().equals(actual.issuer())
            && expected.applicationId().equals(actual.applicationId())
            && expected.applicationType().equals(actual.applicationType())
            && expected.frontendId().equals(actual.frontendId())
            && expected.userId().equals(actual.userId())
            && expected.rootSid().equals(actual.rootSid())
            && expected.sessionId().equals(actual.sessionId())
            && expected.sessionGeneration() == actual.sessionGeneration()
            && expected.terminal().equals(actual.terminal())
            && expected.transactionId().equals(actual.transactionId());
    }

    private String preparationKey(String scope, String frontendId, String transactionId) {
        return IamProtocolConstants.APPLICATION_PREPARATION_KEY_PREFIX
            + scope + ":" + frontendId + ":" + transactionId;
    }

    /**
     * 判断现有占位是否可直接复用。
     *
     * @param session       现有占位
     * @param applicationId 目标应用
     * @param frontendId    受信前端入口
     * @param terminal      终端
     * @param rootSid       根会话
     * @param now           当前时间
     * @return 是否复用
     */
    private boolean isSameSession(
        IamApplicationSession session,
        String applicationId,
        String frontendId,
        String terminal,
        String rootSid,
        long now
    ) {
        return session != null
            && session.expiresAt() > now
            && applicationId.equals(session.applicationId())
            && frontendId.equals(session.frontendId())
            && terminal.equals(session.terminal())
            && rootSid.equals(session.rootSid());
    }

    /**
     * 分配应用类型内的新会话代次。
     *
     * @param scope 权威账号与应用类型作用域
     * @return 新代次
     */
    private long nextGeneration(String scope) {
        RAtomicLong counter = RedisUtils.getClient()
            .getAtomicLong(IamProtocolConstants.APPLICATION_GENERATION_KEY_PREFIX + scope);
        long generation = counter.incrementAndGet();
        counter.expire(properties.getRootSessionTtl());
        return generation;
    }

    /**
     * 撤销仍精确匹配的当前应用占位。
     *
     * @return 是否删除
     */
    private boolean revokeActiveIfMatches(
        Long userId,
        String applicationType,
        String rootSid,
        String applicationId,
        String frontendId,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        if (userId == null || applicationType == null) {
            return false;
        }
        String scope = sessionScope(userId, applicationType, rootSid);
        String key = activeSessionKey(scope);
        RBucket<String> bucket = RedisUtils.getClient().getBucket(key);
        String payload = bucket.get();
        IamApplicationSession active = readSession(payload);
        if (!matches(
            active,
            applicationId,
            frontendId,
            rootSid,
            sessionId,
            sessionGeneration,
            terminal
        )) {
            return false;
        }
        return deleteIfPayloadMatches(bucket, payload);
    }

    /**
     * 仅当 Redis 当前载荷仍是读取到的载荷时删除，避免检查与删除之间的并发覆盖。
     *
     * @param bucket         应用会话占位
     * @param expectedPayload 已读取并完成匹配的载荷
     * @return 是否删除
     */
    static boolean deleteIfPayloadMatches(RBucket<String> bucket, String expectedPayload) {
        return expectedPayload != null && bucket.compareAndSet(expectedPayload, null);
    }

    private boolean matches(
        IamApplicationSession active,
        String applicationId,
        String frontendId,
        String rootSid,
        String sessionId,
        long sessionGeneration,
        String terminal
    ) {
        return active != null
            && applicationId.equals(active.applicationId())
            && frontendId.equals(active.frontendId())
            && rootSid.equals(active.rootSid())
            && sessionId.equals(active.sessionId())
            && sessionGeneration == active.sessionGeneration()
            && terminal.equals(active.terminal());
    }

    /**
     * 构造应用占位键。
     *
     * @param scope 授权主体与应用类型
     * @return Redis 键
     */
    private String activeSessionKey(String scope) {
        return IamProtocolConstants.APPLICATION_SESSION_KEY_PREFIX + scope;
    }

    /**
     * 构造同一账号在同一中心根会话下唯一的应用作用域。
     *
     * @param userId          权威账号主键
     * @param applicationType 应用类型
     * @param rootSid         中心根会话标识
     * @return 应用会话作用域
     */
    static String sessionScope(Long userId, String applicationType, String rootSid) {
        return userId + ":" + applicationType + ":" + rootSid;
    }

    /**
     * 应用激活结果。
     *
     * @param session          当前应用会话
     * @param newlyActivated   是否本次新替换占位
     * @param activeKey        有效占位键
     * @param transactionKey   幂等事务键
     * @param previousPayload  前一占位载荷
     * @param activatedPayload 本次占位载荷
     */
    public record ActivationResult(
        IamApplicationSession session,
        boolean newlyActivated,
        String activeKey,
        String transactionKey,
        String previousPayload,
        String activatedPayload
    ) {

        /**
         * 复用已有会话，无需回滚。
         *
         * @param session 当前会话
         * @return 激活结果
         */
        public static ActivationResult reused(IamApplicationSession session) {
            return new ActivationResult(session, false, null, null, null, null);
        }

        /**
         * 新建可回滚的激活结果。
         *
         * @param session          新应用会话
         * @param activeKey        有效占位键
         * @param transactionKey   幂等事务键
         * @param previousPayload  前一占位载荷
         * @param activatedPayload 本次占位载荷
         * @return 激活结果
         */
        public static ActivationResult activated(
            IamApplicationSession session,
            String activeKey,
            String transactionKey,
            String previousPayload,
            String activatedPayload
        ) {
            return new ActivationResult(
                session,
                true,
                activeKey,
                transactionKey,
                previousPayload,
                activatedPayload
            );
        }
    }
}
