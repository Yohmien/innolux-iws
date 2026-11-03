/*
 * @Date         : 2026-10-31
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P5 通道可达性
 * @Version      : V1.0
 * @Description  : 验证显式阻挡图的传递、分支、多阻挡源和配置拒绝；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.allocation.space.reachability;

import com.innolux.iws.common.core.exception.ServiceException;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilityEvaluator.BlockingEdge;
import com.innolux.iws.wms.allocation.space.reachability.WmsChannelReachabilityEvaluator.Member;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通道显式阻挡图可达性内核测试。 */
@Tag("local")
class WmsChannelReachabilityEvaluatorTest {

    private final WmsChannelReachabilityEvaluator evaluator =
        new WmsChannelReachabilityEvaluator();

    @Test
    void occupiedBlockerMakesEveryDescendantUnreachable() {
        WmsChannelReachabilityResult result = evaluator.evaluate(
            members(10, 20, 30),
            List.of(edge(10, 20), edge(20, 30)),
            Set.of(10L)
        );

        assertThat(result.reachableStorageIds()).containsExactly(10L);
        assertThat(result.unreachableStorageIds()).containsExactly(20L, 30L);
        assertThat(result.occupiedBlockers(20)).containsExactly(10L);
        assertThat(result.occupiedBlockers(30)).containsExactly(10L);
    }

    @Test
    void onlyDescendantsOfOccupiedBlockerAreUnreachable() {
        WmsChannelReachabilityResult result = evaluator.evaluate(
            members(10, 20, 30),
            List.of(edge(10, 20), edge(20, 30)),
            Set.of(20L)
        );

        assertThat(result.reachableStorageIds()).containsExactly(10L, 20L);
        assertThat(result.unreachableStorageIds()).containsExactly(30L);
        assertThat(result.occupiedBlockers(30)).containsExactly(20L);
    }

    @Test
    void noOccupiedBlockerLeavesAllMembersReachable() {
        WmsChannelReachabilityResult result = evaluator.evaluate(
            members(30, 10, 20),
            List.of(edge(10, 20), edge(20, 30)),
            Set.of()
        );

        assertThat(result.orderedStorageIds()).containsExactly(30L, 10L, 20L);
        assertThat(result.reachableStorageIds()).containsExactly(30L, 10L, 20L);
        assertThat(result.unreachableStorageIds()).isEmpty();
    }

    @Test
    void diamondClosureAndMultipleOccupiedBlockersAreReportedDeterministically() {
        WmsChannelReachabilityResult result = evaluator.evaluate(
            members(10, 20, 30, 40),
            List.of(edge(10, 20), edge(10, 30), edge(20, 40), edge(30, 40)),
            Set.of(30L, 10L)
        );

        assertThat(result.unreachableStorageIds()).containsExactly(20L, 30L, 40L);
        assertThat(result.occupiedBlockers(40)).containsExactly(10L, 30L);
        assertThat(result.occupiedBlockers(20)).containsExactly(10L);
    }

    @Test
    void cycleIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            members(10, 20, 30),
            List.of(edge(10, 20), edge(20, 30), edge(30, 10)),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_GRAPH)
            .hasMessageContaining("环");
    }

    @Test
    void selfLoopIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            members(10),
            List.of(edge(10, 10)),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_GRAPH)
            .hasMessageContaining("自环");
    }

    @Test
    void duplicateEdgeIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            members(10, 20),
            List.of(edge(10, 20), edge(10, 20)),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_GRAPH)
            .hasMessageContaining("重复");
    }

    @Test
    void edgeOutsideTheChannelIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            members(10, 20),
            List.of(edge(10, 30)),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_GRAPH)
            .hasMessageContaining("不属于当前通道");
    }

    @Test
    void duplicateMemberAndDuplicateAccessOrderAreRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            List.of(new Member(10, 1), new Member(10, 2)),
            List.of(),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_INPUT)
            .hasMessageContaining("储位重复");

        assertThatThrownBy(() -> evaluator.evaluate(
            List.of(new Member(10, 1), new Member(20, 1)),
            List.of(),
            Set.of()
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_INPUT)
            .hasMessageContaining("访问顺序重复");
    }

    @Test
    void occupiedStorageOutsideTheChannelIsRejected() {
        assertThatThrownBy(() -> evaluator.evaluate(
            members(10, 20),
            List.of(),
            Set.of(30L)
        ))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining(WmsChannelReachabilityEvaluator.ERROR_INVALID_INPUT)
            .hasMessageContaining("活动阻挡储位不属于当前通道");
    }

    private List<Member> members(long... storageIds) {
        return java.util.stream.IntStream.range(0, storageIds.length)
            .mapToObj(index -> new Member(storageIds[index], index + 1))
            .toList();
    }

    private BlockingEdge edge(long blockerStorageId, long blockedStorageId) {
        return new BlockingEdge(blockerStorageId, blockedStorageId);
    }
}
