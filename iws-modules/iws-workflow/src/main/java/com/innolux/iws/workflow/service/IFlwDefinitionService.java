/*
 * @Date         : 2026-09-19
 * @Author       : YONGJIE.PENG
 * @Email        : YONGJIE.PENG@INNOLUX.COM
 * @Job          : INNOLUX IWS 标准化重构
 * @Module       : IWS 智能仓储管理系统
 * @Version      : V1.0
 * @Description  : 统一前后端命名、基础架构、数据库脚本与智能仓储业务入口；
 * @ModifyRecord : V1.0 新建；
 */

package com.innolux.iws.workflow.service;

import jakarta.servlet.http.HttpServletResponse;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import com.innolux.iws.workflow.domain.vo.FlowDefinitionVo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collection;

/**
 * 流程定义 服务层
 *
 * @author YONGJIE.PENG
 */
public interface IFlwDefinitionService {

    /**
     * 查询流程定义列表
     *
     * @param flowDefinition 参数
     * @param pageQuery      分页
     * @return 返回分页列表
     */
    PageResult<FlowDefinitionVo> queryList(FlowDefinition flowDefinition, PageQuery pageQuery);

    /**
     * 查询未发布的流程定义列表
     *
     * @param flowDefinition 参数
     * @param pageQuery      分页
     * @return 返回分页列表
     */
    PageResult<FlowDefinitionVo> unPublishList(FlowDefinition flowDefinition, PageQuery pageQuery);

    /**
     * 发布流程定义
     *
     * @param id 流程定义id
     * @return 是否发布成功
     */
    boolean publish(Long id);

    /**
     * 导出流程定义
     *
     * @param id       流程定义id
     * @param response 响应
     * @throws IOException 异常
     */
    void exportDef(Long id, HttpServletResponse response) throws IOException;

    /**
     * 导入流程定义
     *
     * @param file     文件
     * @param category 分类
     * @return 是否导入成功
     */
    boolean importJson(MultipartFile file, String category);

    /**
     * 删除流程定义
     *
     * @param ids 流程定义id
     * @return 是否删除成功
     */
    boolean removeDef(Collection<Long> ids);

}
