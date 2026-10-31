<script setup lang="ts">
import RefreshButton from "../components/RefreshButton.vue";
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
        <span class="eyebrow">05 / NOTIFICATIONS</span>
        <h1>与你有关的，每一步进展。</h1>
      </div>
      <RefreshButton :refresh="load">刷新通知</RefreshButton>
    </div>
    <article v-for="(n, index) in items" :key="n.id" class="panel notice-row">
      <span class="notice-index" aria-hidden="true">{{
        String(index + 1).padStart(2, "0")
      }}</span>
      <div>
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
      </div>
    </article>
    <el-empty v-if="!items.length" description="暂无通知" />
  </section>
</template>
