/*
 * @Date         : 2026-10-29
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : WMS 在库管理 P4 物料 trace 持久化
 * @Version      : V1.0
 * @Description  : 只提供物料 trace 三表的插入和只读快照查询；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.wms.material.trace.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 物料 trace 追加只读 Mapper。
 *
 * <p>接口刻意不继承 BaseMapper，也不提供 update/delete 方法。</p>
 *
 * @author YONGJIE.PENG
 */
public interface WmsMaterialTraceMapper {

    /**
     * 插入事实头。
     *
     * @param row 完整事实头
     * @return 影响行数
     */
    int insertHistory(WmsMaterialTraceHistoryRow row);

    /**
     * 插入事实项。
     *
     * @param row 完整事实项
     * @return 影响行数
     */
    int insertItem(WmsMaterialTraceItemRow row);

    /**
     * 插入生产家谱边。
     *
     * @param row 完整家谱边
     * @return 影响行数
     */
    int insertEdge(WmsMaterialTraceEdgeRow row);

    /**
     * 按效果键读取事实头。
     *
     * @param effectKey 领域效果键
     * @return 事实头或 null
     */
    WmsMaterialTraceHistoryRow selectHistoryByEffectKey(@Param("effectKey") String effectKey);

    /**
     * 按效果键读取并锁定事实头，用于并发重放复核。
     *
     * @param effectKey 领域效果键
     * @return 事实头或 null
     */
    WmsMaterialTraceHistoryRow selectHistoryByEffectKeyForUpdate(@Param("effectKey") String effectKey);

    /**
     * 按 trace ID 读取事实头。
     *
     * @param traceId trace ID
     * @return 事实头或 null
     */
    WmsMaterialTraceHistoryRow selectHistoryByTraceId(@Param("traceId") long traceId);

    /**
     * 按 trace ID 读取并共享锁定事实头，用于补偿链复核。
     *
     * @param traceId trace ID
     * @return 事实头或 null
     */
    WmsMaterialTraceHistoryRow selectHistoryByTraceIdForShare(@Param("traceId") long traceId);

    /**
     * 读取一次事实的全部物料项。
     *
     * @param traceId trace ID
     * @return 物料项
     */
    List<WmsMaterialTraceItemRow> selectItemsByTraceId(@Param("traceId") long traceId);

    /**
     * 读取一次事实的全部生产家谱边。
     *
     * @param traceId trace ID
     * @return 生产家谱边
     */
    List<WmsMaterialTraceEdgeRow> selectEdgesByTraceId(@Param("traceId") long traceId);
}
