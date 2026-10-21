<script setup lang="ts">
import { ref, onMounted } from "vue";
import { read } from "../api";
interface Room {
  id: string;
  name: string;
  building: string;
  available: boolean;
}
interface Seat {
  id: string;
  code: string;
  powerType: string;
}
const rooms = ref<Room[]>([]),
  seats = ref<Seat[]>([]),
  selected = ref(""),
  loading = ref(false);
async function load() {
  loading.value = true;
  try {
    rooms.value = (await read<{ items: Room[] }>("/rooms")).items;
  } catch {
  } finally {
    loading.value = false;
  }
}
async function select(r: Room) {
  selected.value = r.name;
  try {
    seats.value = await read<Seat[]>("/rooms/" + r.id + "/seats");
  } catch {}
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">M1 · 学习空间</span>
        <h1>找到适合你的学习空间</h1>
        <p>查看教室、座位与当前维修状态。</p>
      </div>
      <el-button @click="load" :loading="loading">刷新状态</el-button>
    </div>
    <div class="room-grid" v-loading="loading">
      <article v-for="r in rooms" :key="r.id" class="room-card">
        <div class="room-top">
          <span>{{ r.building }}</span
          ><el-tag :type="r.available ? 'success' : 'warning'">{{
            r.available ? "当前可用" : "维修限制中"
          }}</el-tag>
        </div>
        <h2>{{ r.name }}</h2>
        <p>{{ r.available ? "空间状态正常" : "存在尚未解除的维修来源" }}</p>
        <el-button @click="select(r)">查看座位</el-button>
      </article>
    </div>
    <article v-if="selected" class="panel">
      <h2>{{ selected }} · 座位</h2>
      <el-table :data="seats"
        ><el-table-column prop="code" label="座位编号" /><el-table-column
          prop="powerType"
          label="插座类型"
      /></el-table>
      <p class="muted">座位预约、每日动态码签到及插座类型筛选已接入；请从“座位预约”进入完整操作。</p>
    </article>
    <el-empty
      v-if="!loading && !rooms.length"
      description="暂无空间数据，可刷新重试"
    />
  </section>
</template>
