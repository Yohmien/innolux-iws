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

package com.innolux.iws.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import com.innolux.iws.common.core.domain.PageResult;
import com.innolux.iws.common.core.domain.R;
import com.innolux.iws.common.core.enums.PushSourceEnum;
import com.innolux.iws.common.core.enums.PushTypeEnum;
import com.innolux.iws.common.core.service.DictService;
import com.innolux.iws.common.log.annotation.Log;
import com.innolux.iws.common.log.enums.BusinessType;
import com.innolux.iws.common.mybatis.core.page.PageQuery;
import com.innolux.iws.common.redis.annotation.RepeatSubmit;
import com.innolux.iws.common.web.core.BaseController;
import com.innolux.iws.system.api.MessageService;
import com.innolux.iws.system.api.domain.PushPayloadDTO;
import com.innolux.iws.system.domain.bo.SysNoticeBo;
import com.innolux.iws.system.domain.vo.SysNoticeVo;
import com.innolux.iws.system.service.ISysNoticeService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 公告 信息操作处理
 *
 * @author YONGJIE.PENG
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/notice")
public class SysNoticeController extends BaseController {

    private final ISysNoticeService noticeService;
    private final DictService dictService;
    private final MessageService messageService;

    /**
     * 分页查询通知公告列表。
     *
     * @param notice    查询条件
     * @param pageQuery 分页参数
     * @return 公告分页结果
     */
    @SaCheckPermission("system:notice:list")
    @GetMapping("/list")
    public R<PageResult<SysNoticeVo>> list(SysNoticeBo notice, PageQuery pageQuery) {
        return R.ok(noticeService.selectPageNoticeList(notice, pageQuery));
    }

    /**
     * 根据通知公告编号获取详细信息
     *
     * @param noticeId 公告ID
     * @return 公告详情
     */
    @SaCheckPermission("system:notice:query")
    @GetMapping(value = "/{noticeId}")
    public R<SysNoticeVo> getInfo(@PathVariable Long noticeId) {
        return R.ok(noticeService.selectNoticeById(noticeId));
    }

    /**
     * 新增通知公告，并向在线用户广播公告摘要。
     *
     * @param notice 公告参数
     * @return 操作结果
     */
    @SaCheckPermission("system:notice:add")
    @Log(title = "通知公告", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated @RequestBody SysNoticeBo notice) {
        int rows = noticeService.insertNotice(notice);
        if (rows <= 0) {
            return R.fail();
        }
        String type = dictService.getDictLabel("sys_notice_type", notice.getNoticeType());
        Map<String, Object> data = new HashMap<>(4);
        data.put("noticeType", notice.getNoticeType());
        data.put("noticeTypeLabel", type);
        data.put("noticeTitle", notice.getNoticeTitle());
        data.put("noticeId", notice.getNoticeId());
        data.put("noticeContent", notice.getNoticeContent());
        data.put("status", notice.getStatus());
        messageService.publishAll(PushPayloadDTO.of(
            PushTypeEnum.NOTICE,
            PushSourceEnum.NOTICE,
            "[" + type + "] " + notice.getNoticeTitle(),
            data,
            "/system/notice?noticeId=" + notice.getNoticeId()
        ));
        return R.ok();
    }

    /**
     * 修改通知公告。
     *
     * @param notice 公告参数
     * @return 操作结果
     */
    @SaCheckPermission("system:notice:edit")
    @Log(title = "通知公告", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated @RequestBody SysNoticeBo notice) {
        return toAjax(noticeService.updateNotice(notice));
    }

    /**
     * 删除通知公告
     *
     * @param noticeIds 公告ID串
     * @return 操作结果
     */
    @SaCheckPermission("system:notice:remove")
    @Log(title = "通知公告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{noticeIds}")
    public R<Void> remove(@PathVariable Long[] noticeIds) {
        return toAjax(noticeService.deleteNoticeByIds(noticeIds));
    }
}
