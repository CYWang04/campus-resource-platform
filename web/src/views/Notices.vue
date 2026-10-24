<script setup lang="ts">
import { ref, onMounted } from "vue";
import { read } from "../api";
interface Notice {
  id: string;
  message: string;
  mailState: string;
}
const items = ref<Notice[]>([]);
async function load() {
  try {
    items.value = await read<Notice[]>("/notifications");
  } catch {}
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">消息中心</span>
        <h1>与你有关的服务进展</h1>
      </div>
      <el-button @click="load">刷新通知</el-button>
    </div>
    <article v-for="n in items" :key="n.id" class="panel">
      <p>{{ n.message }}</p>
      <small class="muted"
        >邮件投递：{{
          n.mailState === "SENT"
            ? "已交给邮件服务"
            : n.mailState === "FAILED"
              ? "投递失败"
              : "等待投递"
        }}</small
      >
    </article>
    <el-empty v-if="!items.length" description="暂无通知" />
  </section>
</template>
