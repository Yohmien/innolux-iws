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

package com.innolux.iws.demo.controller;

import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.demo.domain.Document;
import com.innolux.iws.demo.esmapper.DocumentMapper;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索引擎 crud 演示案例
 *
 * @author YONGJIE.PENG
 */
@ConditionalOnProperty(value = "easy-es.enable", havingValue = "true")
@RequiredArgsConstructor
@RestController
@RequestMapping("/es")
public class EsCrudController {

    private final DocumentMapper documentMapper;

    /**
     * 查询(指定)
     *
     * @param title 标题
     */
    @GetMapping("/select")
    public Document select(String title) {
        LambdaEsQueryWrapper<Document> wrapper = new LambdaEsQueryWrapper<>();
        wrapper.eq(Document::getTitle, title);
        return documentMapper.selectOne(wrapper);
    }

    /**
     * 搜索(模糊)
     *
     * @param key 搜索关键字
     */
    @GetMapping("/search")
    public List<Document> search(String key) {
        LambdaEsQueryWrapper<Document> wrapper = new LambdaEsQueryWrapper<>();
        wrapper.like(Document::getTitle, key);
        return documentMapper.selectList(wrapper);
    }

    /**
     * 插入
     */
    @PostMapping("/insert")
    public Integer insert(@RequestBody Document document) {
        return documentMapper.insert(document);
    }

    /**
     * 更新
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody Document document) {
        // 测试更新 更新有两种情况 分别演示如下:
        // case1: 已知id, 根据id更新 (为了演示方便,此id是从上一步查询中复制过来的,实际业务可以自行查询)
        documentMapper.updateById(document);

        // case2: id未知, 根据条件更新
//        LambdaEsUpdateWrapper<Document> wrapper = new LambdaEsUpdateWrapper<>();
//        wrapper.like(Document::getTitle, document.getTitle());
//        Document document2 = new Document();
//        document2.setTitle(document.getTitle());
//        document2.setContent(document.getContent());
//        documentMapper.update(document2, wrapper);

        return R.ok();
    }

    /**
     * 删除
     *
     * @param id 主键
     */
    @DeleteMapping("/delete/{id}")
    public R<Integer> delete(@PathVariable String id) {
        // 测试删除数据 删除有两种情况:根据id删或根据条件删
        return R.ok(documentMapper.deleteById(id));
    }

}
