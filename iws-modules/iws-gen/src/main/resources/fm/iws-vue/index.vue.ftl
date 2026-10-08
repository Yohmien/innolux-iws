<!--
 - @Date         : 2026-09-29
 - @Author       : YONGJIE.PENG
 - @Email        : YONGJIE.PENG@INNOLUX.COM
 - @Job          : INNOLUX IWS 标准化重构
 - @Module       : IWS 标准表数据页前端
 - @Version      : V1.0
 - @Description  : 标准 CRUD 平铺页面模板，页面能力由 IwsCrudPage 和字段元数据驱动；
 - @ModifyRecord : V1.0 新建；
-->

<template>
  <IwsCrudPage table-code="${tableCode}" />
</template>

<script setup name="${BusinessName}" lang="ts">
import { IwsCrudPage } from '@/components/IwsCrudPage';
</script>
