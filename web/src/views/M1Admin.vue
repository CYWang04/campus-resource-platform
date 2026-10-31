<script setup lang="ts">
import RefreshButton from "../components/RefreshButton.vue";
import { onMounted, ref } from "vue";
import { api, read } from "../api";
interface Room {
  id: string;
  name: string;
  building: string;
  departmentCode?: string;
  openTime: string;
  closeTime: string;
}
const rooms = ref<Room[]>([]),
  stats = ref<{
    byStatus: { status: string; count: number }[];
    byRoom: { roomName: string; count: number }[];
    violations: number;
  } | null>(null),
  room = ref({
    name: "",
    building: "",
    departmentCode: "CS",
    openTime: "07:00:00",
    closeTime: "23:00:00",
    active: true,
  }),
  seat = ref({ roomId: "", code: "", powerType: "FIXED", active: true });
async function load() {
  rooms.value = (await read<{ items: Room[] }>("/rooms")).items;
  stats.value = await read("/admin/reservation-statistics");
}
async function addRoom() {
  await api.post("/admin/rooms", room.value);
  room.value = {
    name: "",
    building: "",
    departmentCode: "CS",
    openTime: "07:00:00",
    closeTime: "23:00:00",
    active: true,
  };
  await load();
}
async function addSeat() {
  await api.post("/admin/seats", seat.value);
  seat.value = { roomId: "", code: "", powerType: "FIXED", active: true };
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">06 / RESOURCE MANAGEMENT</span>
        <h1>让每一处空间，有序运转。</h1>
        <p>登记资源、设置院系和开放时间，并查看预约分布。</p>
      </div>
      <RefreshButton :refresh="load">刷新</RefreshButton>
    </div>
    <div class="form-grid resource-forms">
      <article class="panel">
        <h2>登记教室</h2>
        <el-form label-position="top" @submit.prevent="addRoom"
          ><el-form-item label="名称"
            ><el-input v-model="room.name" /></el-form-item
          ><el-form-item label="楼宇"
            ><el-input v-model="room.building" /></el-form-item
          ><el-form-item label="开放院系"
            ><el-input
              v-model="room.departmentCode"
              placeholder="留空表示全部院系"
          /></el-form-item>
          <div class="form-grid">
            <el-form-item label="开放"
              ><el-time-select
                v-model="room.openTime"
                start="06:00"
                step="00:30"
                end="22:00" /></el-form-item
            ><el-form-item label="关闭"
              ><el-time-select
                v-model="room.closeTime"
                start="07:00"
                step="00:30"
                end="23:30"
            /></el-form-item>
          </div>
          <el-button
            type="primary"
            native-type="submit"
            :disabled="!room.name || !room.building"
            >登记</el-button
          ></el-form
        >
      </article>
      <article class="panel">
        <h2>登记座位</h2>
        <el-form label-position="top" @submit.prevent="addSeat"
          ><el-form-item label="教室"
            ><el-select v-model="seat.roomId"
              ><el-option
                v-for="r in rooms"
                :key="r.id"
                :label="r.name"
                :value="r.id" /></el-select></el-form-item
          ><el-form-item label="座位编号"
            ><el-input v-model="seat.code" /></el-form-item
          ><el-form-item label="插座"
            ><el-select v-model="seat.powerType"
              ><el-option label="固定插座" value="FIXED" /><el-option
                label="移动导轨"
                value="RAIL" /><el-option
                label="无插座"
                value="NONE" /></el-select></el-form-item
          ><el-button
            type="primary"
            native-type="submit"
            :disabled="!seat.roomId || !seat.code"
            >登记</el-button
          ></el-form
        >
      </article>
    </div>
    <article class="panel">
      <h2>预约统计</h2>
      <p>累计违约：{{ stats?.violations ?? 0 }}</p>
      <el-table :data="stats?.byStatus ?? []"
        ><el-table-column prop="status" label="状态" /><el-table-column
          prop="count"
          label="数量"
      /></el-table>
      <h3>教室使用量</h3>
      <el-table :data="stats?.byRoom ?? []"
        ><el-table-column prop="roomName" label="教室" /><el-table-column
          prop="count"
          label="预约次数"
      /></el-table>
    </article>
  </section>
</template>
