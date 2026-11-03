/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 纯装载树核测试
 * @Version      : V1.0
 * @Description  : 验证装载森林引用、单父、环、深度、稳定索引与输入不可变性；
 * @ModifyRecord : V1.0 新建；
 */
package com.innolux.iws.wms.load.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("local")
class LoadTreeKernelTest {
    @Test
    void parsesMultipleRootsAndPreservesEmptyCarrierInIdOrder() {
        List<LoadNode> nodes = List.of(
            carrier(40), material(30), carrier(10), material(20), material(50));
        List<LoadEdge> edges = List.of(
            new LoadEdge(30, 10), new LoadEdge(20, 10));

        LoadTree tree = LoadTreeKernel.parse(nodes, edges);

        assertThat(tree.rootIds()).containsExactly(10L, 40L, 50L);
        assertThat(tree.materialLeafIds()).containsExactly(20L, 30L, 50L);
        assertThat(tree.rootIdOf(30)).isEqualTo(10);
        assertThat(tree.pathToRoot(30)).containsExactly(30L, 10L);
        assertThat(tree.materialLeafIdsByRoot().get(10L)).containsExactly(20L, 30L);
        assertThat(tree.materialLeafIdsByRoot().get(50L)).containsExactly(50L);
        assertThat(tree.topLevelSlotCount()).isEqualTo(3);
        assertThat(tree.nodes()).extracting(LoadNode::id).containsExactly(10L, 20L, 30L, 40L, 50L);
        assertThat(tree.edges()).containsExactly(new LoadEdge(20, 10), new LoadEdge(30, 10));
    }

    @Test
    void acceptsDepthSixteenAndRejectsSeventeenAsOneWholeTree() {
        List<LoadNode> sixteen = new ArrayList<>();
        List<LoadEdge> sixteenEdges = new ArrayList<>();
        for (int depth = 1; depth <= 16; depth++) {
            long id = 100L + depth;
            sixteen.add(depth == 16 ? material(id) : carrier(id));
            if (depth > 1) {
                sixteenEdges.add(new LoadEdge(id, id - 1));
            }
        }

        LoadTree boundary = LoadTreeKernel.parse(sixteen, sixteenEdges,
            new LoadLimits(16));
        assertThat(boundary.pathToRoot(116)).hasSize(16);

        List<LoadNode> seventeen = new ArrayList<>();
        List<LoadEdge> seventeenEdges = new ArrayList<>();
        for (int depth = 1; depth <= 16; depth++) {
            long id = 100L + depth;
            seventeen.add(carrier(id));
            if (depth > 1) {
                seventeenEdges.add(new LoadEdge(id, id - 1));
            }
        }
        seventeen.add(material(117));
        seventeenEdges.add(new LoadEdge(117, 116));
        assertCode(() -> LoadTreeKernel.parse(seventeen, seventeenEdges, new LoadLimits(16)),
            LoadKernelException.Code.LOAD_DEPTH_EXCEEDED);
    }

    @Test
    void enforcesIndependentLimitAndRootDepthOne() {
        LoadTree tree = LoadTreeKernel.parse(
            List.of(carrier(1), carrier(2)),
            List.of(new LoadEdge(2, 1)),
            new LoadLimits(2));
        assertThat(tree.pathToRoot(2)).containsExactly(2L, 1L);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), carrier(2), carrier(3)),
            List.of(new LoadEdge(2, 1), new LoadEdge(3, 2)),
            new LoadLimits(2)), LoadKernelException.Code.LOAD_DEPTH_EXCEEDED);
    }

    @Test
    void rejectsMissingReferencesWrongParentAndMaterialWithChildren() {
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1)), List.of(new LoadEdge(2, 1))),
            LoadKernelException.Code.LOAD_CHILD_UNKNOWN);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(material(2)), List.of(new LoadEdge(2, 1))),
            LoadKernelException.Code.LOAD_PARENT_UNKNOWN);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(material(1), material(2)), List.of(new LoadEdge(2, 1))),
            LoadKernelException.Code.LOAD_MATERIAL_HAS_CHILD);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), material(2), carrier(3)), List.of(
                new LoadEdge(2, 1), new LoadEdge(3, 2))),
            LoadKernelException.Code.LOAD_MATERIAL_HAS_CHILD);
    }

    @Test
    void rejectsDuplicateRelationsAndMultipleParentsWithoutMapCollapse() {
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), carrier(2)), List.of(
                new LoadEdge(2, 1), new LoadEdge(2, 1))),
            LoadKernelException.Code.LOAD_EDGE_DUPLICATE);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), carrier(2), carrier(3)), List.of(
                new LoadEdge(3, 1), new LoadEdge(3, 2))),
            LoadKernelException.Code.LOAD_MULTIPLE_PARENTS);
    }

    @Test
    void rejectsSelfReferenceAndMultinodeCycles() {
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1)), List.of(new LoadEdge(1, 1))),
            LoadKernelException.Code.LOAD_SELF_PARENT);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), carrier(2)), List.of(
                new LoadEdge(2, 1), new LoadEdge(1, 2))),
            LoadKernelException.Code.LOAD_CYCLE);
    }

    @Test
    void rejectsDuplicateNodeAndPhysicalIdentities() {
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1), carrier(1)), List.of()),
            LoadKernelException.Code.LOAD_NODE_DUPLICATE);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(new LoadNode(1, LoadNode.Type.MATERIAL, 10L, null),
                new LoadNode(2, LoadNode.Type.MATERIAL, 10L, null)), List.of()),
            LoadKernelException.Code.LOAD_NODE_DUPLICATE);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(new LoadNode(1, LoadNode.Type.CARRIER, null, 10L),
                new LoadNode(2, LoadNode.Type.CARRIER, null, 10L)), List.of()),
            LoadKernelException.Code.LOAD_NODE_DUPLICATE);
    }

    @Test
    void rejectsNullInputsAndInvalidNodeIdentity() {
        assertCode(() -> LoadTreeKernel.parse(null, List.of()),
            LoadKernelException.Code.LOAD_INPUT_NULL);
        assertCode(() -> LoadTreeKernel.parse(
            List.of(carrier(1)), Arrays.asList((LoadEdge) null)),
            LoadKernelException.Code.LOAD_INPUT_NULL);
        assertCode(() -> new LoadNode(1, LoadNode.Type.MATERIAL, null, null),
            LoadKernelException.Code.LOAD_NODE_INVALID);
        assertCode(() -> new LoadNode(1, LoadNode.Type.MATERIAL, 10L, 20L),
            LoadKernelException.Code.LOAD_NODE_INVALID);
        assertCode(() -> new LoadNode(1, LoadNode.Type.CARRIER, 10L, null),
            LoadKernelException.Code.LOAD_NODE_INVALID);
        assertCode(() -> new LoadLimits(0),
            LoadKernelException.Code.LOAD_LIMIT_INVALID);
    }

    @Test
    void sourceAndResultCollectionsAreImmutableSnapshots() {
        List<LoadNode> nodes = new ArrayList<>(List.of(carrier(2), material(1)));
        List<LoadEdge> edges = new ArrayList<>(List.of(new LoadEdge(1, 2)));
        LoadTree tree = LoadTreeKernel.parse(nodes, edges);

        nodes.clear();
        edges.clear();

        assertThat(tree.nodes()).extracting(LoadNode::id).containsExactly(1L, 2L);
        assertThat(tree.edges()).containsExactly(new LoadEdge(1, 2));
        assertThatThrownBy(() -> tree.nodes().add(material(3)))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tree.rootIds().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tree.pathToRoot(1).add(3L))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static LoadNode material(long id) {
        return new LoadNode(id, LoadNode.Type.MATERIAL, id, null);
    }

    private static LoadNode carrier(long id) {
        return new LoadNode(id, LoadNode.Type.CARRIER, null, id);
    }

    private static void assertCode(Runnable action, LoadKernelException.Code code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(LoadKernelException.class,
                exception -> assertThat(exception.code()).isEqualTo(code.name()));
    }
}
