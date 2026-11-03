/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 锁位短事务集成测试
 * @Version      : V1.0
 * @Description  : 在初始快照后从独立连接提交一次成员锁域变化；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.reservation;

import com.innolux.iws.wms.allocation.reservation.space.WmsAllocationSpaceReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 测试专用成员迁移读取器。 */
final class RetrySpaceReader implements WmsAllocationSpaceReader {

    private final WmsAllocationSpaceReader delegate;

    private final AtomicReference<Move> move = new AtomicReference<>();

    RetrySpaceReader(WmsAllocationSpaceReader delegate) {
        this.delegate = delegate;
    }

    void moveAfterInitialSnapshot(long storageId, long fromChannelId, long toChannelId) {
        move.set(new Move(storageId, fromChannelId, toChannelId));
    }

    @Override
    public Map<Long, Membership> readInitial(Collection<Long> storageIds) {
        Map<Long, Membership> result = delegate.readInitial(storageIds);
        Move pending = move.getAndSet(null);
        if (pending != null) {
            moveFromIndependentConnection(pending);
        }
        return result;
    }

    @Override
    public Map<Long, Membership> readCurrentForUpdate(Collection<Long> storageIds) {
        return delegate.readCurrentForUpdate(storageIds);
    }

    private void moveFromIndependentConnection(Move pending) {
        try (Connection connection = WmsAllocationReservationTestDatabase.openRootConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement member = connection.prepareStatement("""
                    UPDATE wms_storage_access_member
                    SET channel_id = ?, access_order = 1, revision = revision + 1
                    WHERE storage_id = ? AND channel_id = ?
                    """);
                 PreparedStatement oldChannel = connection.prepareStatement("""
                    UPDATE wms_access_channel SET revision = revision + 1 WHERE channel_id = ?
                    """);
                 PreparedStatement newChannel = connection.prepareStatement("""
                    UPDATE wms_access_channel SET revision = revision + 1 WHERE channel_id = ?
                    """)) {
                member.setLong(1, pending.toChannelId());
                member.setLong(2, pending.storageId());
                member.setLong(3, pending.fromChannelId());
                if (member.executeUpdate() != 1) {
                    throw new IllegalStateException("测试成员迁移未命中");
                }
                oldChannel.setLong(1, pending.fromChannelId());
                oldChannel.executeUpdate();
                newChannel.setLong(1, pending.toChannelId());
                newChannel.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("测试成员迁移提交失败", error);
        }
    }

    private record Move(long storageId, long fromChannelId, long toChannelId) {
    }
}
