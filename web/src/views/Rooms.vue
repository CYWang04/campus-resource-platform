<script setup lang="ts">
import RefreshButton from "../components/RefreshButton.vue";
import { ref, onMounted } from "vue";
import { read } from "../api";
import { useSession } from "../session";
const session = useSession();
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
const powerLabels: Record<string, string> = {
  FIXED: "固定插座",
  RAIL: "移动导轨插座",
  NONE: "无插座",
};
const rooms = ref<Room[]>([]),
  seats = ref<Seat[]>([]),
  selected = ref(""),
  loading = ref(false),
  seatsLoading = ref(false);
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
  seats.value = [];
  seatsLoading.value = true;
  try {
    seats.value = await read<Seat[]>("/rooms/" + r.id + "/seats");
  } catch {
  } finally {
    seatsLoading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">01 / STUDY SPACES</span>
        <h1>留一处空间，给专注的你。</h1>
        <p>浏览校园学习空间，查看座位与开放状态。</p>
      </div>
      <RefreshButton :refresh="load">刷新状态</RefreshButton>
    </div>
    <div class="room-list-heading">
      <span>空间目录 / DIRECTORY</span><span>{{ rooms.length }} 处空间</span>
    </div>
    <div class="room-list" v-loading="loading">
      <article v-for="(r, index) in rooms" :key="r.id" class="room-card">
        <span class="room-index" aria-hidden="true">{{
          String(index + 1).padStart(2, "0")
        }}</span>
        <div>
          <div class="room-top">
            <span>{{ r.building }}</span
            ><el-tag :type="r.available ? 'success' : 'warning'">{{
              r.available ? "当前可用" : "维修限制中"
            }}</el-tag>
          </div>
          <h2>{{ r.name }}</h2>
          <p>
            {{
              r.available
                ? "选择空间，进一步查看座位与预约时段。"
                : "该空间正在维修，预约前请留意最新状态。"
            }}
          </p>
        </div>
        <el-button @click="select(r)"
          >查看座位 <span aria-hidden="true">↗</span></el-button
        >
      </article>
    </div>
    <article v-if="selected" class="panel" v-loading="seatsLoading">
      <div class="room-detail-heading">
        <h2>{{ selected }} · 座位目录</h2>
        <router-link
          v-if="session.user?.permissions.includes('reservations:read')"
          to="/reservations"
          >前往预约 ↗</router-link
        >
      </div>
      <el-table :data="seats"
        ><el-table-column prop="code" label="座位编号" /><el-table-column
          label="插座配置"
          ><template #default="{ row }">{{
            powerLabels[row.powerType] ?? row.powerType
          }}</template></el-table-column
        ></el-table
      >
      <p class="muted">
        这里展示登记在册的座位；具体时段是否可预约，请前往座位预约查询。
      </p>
    </article>
    <el-empty
      v-if="!loading && !rooms.length"
      description="暂无空间数据，可刷新重试"
    />
  </section>
</template>
