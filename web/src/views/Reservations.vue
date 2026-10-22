<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { command, read } from "../api";

interface Room {
  id: string;
  name: string;
  building: string;
}
interface Seat {
  id: string;
  code: string;
  powerType: string;
  available: boolean;
}
interface Reservation {
  id: string;
  seatId: string;
  roomId: string;
  seatCode: string;
  roomName: string;
  building: string;
  startAt: string;
  endAt: string;
  status: "CONFIRMED" | "CHECKED_IN" | "CANCELLED" | "NO_SHOW" | "CANCELLED_MAINTENANCE";
  purpose: string;
}

function nextWholeHour() {
  const value = new Date();
  value.setMinutes(0, 0, 0);
  value.setHours(value.getHours() + 1);
  return value;
}

const rooms = ref<Room[]>([]);
const seats = ref<Seat[]>([]);
const reservations = ref<Reservation[]>([]);
const violations = ref<{id:string;reason:string;createdAt:string}[]>([]);
const codes = ref<Record<string,string>>({});
const busy = ref(false);
const checking = ref(false);
const createKey = ref(crypto.randomUUID());
const cancelKeys = new Map<string, string>();
const form = ref({
  roomId: "",
  seatId: "",
  startAt: nextWholeHour(),
  durationHours: 1,
  purpose: "",
});

const endAt = computed(
  () => new Date(form.value.startAt.getTime() + form.value.durationHours * 3600000),
);
const canSubmit = computed(
  () =>
    !!form.value.roomId &&
    !!form.value.seatId &&
    !!form.value.purpose.trim() &&
    form.value.startAt.getMinutes() === 0,
);
const labels: Record<string, string> = { CONFIRMED:"待签到",CHECKED_IN:"已签到",CANCELLED:"已取消",NO_SHOW:"未签到违约",CANCELLED_MAINTENANCE:"维修取消" };
const powerLabels: Record<string, string> = {
  FIXED: "固定插座",
  RAIL: "移动导轨插座",
  NONE: "无插座",
};

function iso(value: Date) {
  return value.toISOString();
}
function localTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
}
function canCancel(item: Reservation) {
  return item.status === "CONFIRMED" && new Date(item.startAt).getTime() > Date.now();
}
function disableDate(value: Date) {
  const now = new Date();
  const last = new Date(now.getTime() + 7 * 86400000);
  return value.getTime() < now.setMinutes(0, 0, 0) || value > last;
}

async function load() {
  busy.value = true;
  try {
    const [roomData, reservationData,violationData] = await Promise.all([
      read<{ items: Room[] }>("/rooms"),
      read<{ items: Reservation[] }>("/reservations?page=1&pageSize=100"),
      read<{id:string;reason:string;createdAt:string}[]>("/reservations/violations"),
    ]);
    rooms.value = roomData.items;
    reservations.value = reservationData.items;
    violations.value=violationData;
  } catch {
  } finally {
    busy.value = false;
  }
}

async function showCode(roomId:string){const value=await read<{code:string}>(`/rooms/${roomId}/check-in-code`);codes.value[roomId]=value.code}
async function checkIn(item:Reservation){const code=codes.value[item.roomId]??prompt("请输入教室屏幕上的6位签到码")??"";if(!code)return;await command(`/reservations/${item.id}/check-in`,{code},crypto.randomUUID());await load()}
async function repeat(item:Reservation){form.value.roomId=item.roomId;form.value.seatId=item.seatId;form.value.startAt=nextWholeHour();form.value.durationHours=Math.max(1,Math.min(4,(new Date(item.endAt).getTime()-new Date(item.startAt).getTime())/3600000));form.value.purpose=item.purpose;await loadSeats();form.value.seatId=item.seatId}

async function loadSeats() {
  form.value.seatId = "";
  seats.value = [];
  if (!form.value.roomId || form.value.startAt.getMinutes() !== 0) return;
  checking.value = true;
  try {
    seats.value = await read<Seat[]>(
      `/rooms/${form.value.roomId}/seats?startAt=${encodeURIComponent(iso(form.value.startAt))}&endAt=${encodeURIComponent(iso(endAt.value))}`,
    );
  } catch {
  } finally {
    checking.value = false;
  }
}

async function submit() {
  busy.value = true;
  try {
    await command(
      "/reservations",
      {
        seatId: form.value.seatId,
        startAt: iso(form.value.startAt),
        endAt: iso(endAt.value),
        purpose: form.value.purpose.trim(),
      },
      createKey.value,
    );
    createKey.value = crypto.randomUUID();
    form.value.seatId = "";
    form.value.purpose = "";
    await load();
    await loadSeats();
  } catch {
  } finally {
    busy.value = false;
  }
}

async function cancel(item: Reservation) {
  const key = cancelKeys.get(item.id) ?? crypto.randomUUID();
  cancelKeys.set(item.id, key);
  busy.value = true;
  try {
    await command(`/reservations/${item.id}/cancel`, {}, key);
    await load();
    await loadSeats();
  } catch {
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">M1 · 座位预约</span>
        <h1>为学习安排一个座位</h1>
        <p>可预约未来 7 天内的整点时段，每次 1～4 小时。</p>
      </div>
      <el-button @click="load" :loading="busy">刷新预约</el-button>
    </div>

    <article class="panel">
      <h2>创建预约</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <div class="reservation-grid">
          <el-form-item label="教室">
            <el-select
              v-model="form.roomId"
              placeholder="选择教室"
              @change="loadSeats"
            >
              <el-option
                v-for="room in rooms"
                :key="room.id"
                :value="room.id"
                :label="`${room.building} · ${room.name}`"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="开始时间">
            <el-date-picker
              v-model="form.startAt"
              type="datetime"
              format="YYYY-MM-DD HH:00"
              :disabled-date="disableDate"
              :clearable="false"
              @change="loadSeats"
            />
          </el-form-item>
          <el-form-item label="时长">
            <el-select v-model="form.durationHours" @change="loadSeats">
              <el-option v-for="n in 4" :key="n" :value="n" :label="`${n} 小时`" />
            </el-select>
          </el-form-item>
          <el-form-item label="座位">
            <el-select
              v-model="form.seatId"
              placeholder="先选择教室和时段"
              :loading="checking"
            >
              <el-option
                v-for="seat in seats"
                :key="seat.id"
                :value="seat.id"
                :disabled="!seat.available"
                :label="`${seat.code} · ${powerLabels[seat.powerType] ?? seat.powerType}${seat.available ? '' : ' · 已占用'}`"
              />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="用途">
          <el-input v-model="form.purpose" maxlength="200" placeholder="例如：课程复习" />
        </el-form-item>
        <p class="muted">结束时间：{{ endAt.toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) }}</p>
        <el-button type="primary" native-type="submit" :disabled="!canSubmit" :loading="busy">
          确认预约
        </el-button>
      </el-form>
    </article>

    <article class="panel">
      <h2>我的预约</h2>
      <el-table :data="reservations" v-loading="busy">
        <el-table-column label="地点" min-width="170">
          <template #default="{ row }">{{ row.building }} · {{ row.roomName }} · {{ row.seatCode }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="210">
          <template #default="{ row }">{{ localTime(row.startAt) }}—{{ localTime(row.endAt) }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'CHECKED_IN' ? 'success' : row.status === 'NO_SHOW' ? 'danger' : 'info'">{{ labels[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="330">
          <template #default="{ row }">
            <div class="reservation-actions">
              <el-button
                v-if="canCancel(row)"
                type="danger"
                plain
                size="small"
                :disabled="busy"
                @click="cancel(row)"
              >取消预约</el-button>
              <el-button v-if="row.status==='CONFIRMED'" size="small" @click="showCode(row.roomId)">查看签到码</el-button>
              <el-button v-if="row.status==='CONFIRMED'" size="small" @click="checkIn(row)">签到</el-button>
              <el-button size="small" @click="repeat(row)">再次预约</el-button>
              <small v-if="codes[row.roomId]">今日码：{{codes[row.roomId]}}</small>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!busy && !reservations.length" description="还没有预约记录" />
    </article>
    <article class="panel"><h2>违约记录</h2><el-table :data="violations"><el-table-column prop="reason" label="原因"/><el-table-column prop="createdAt" label="记录时间"/></el-table><el-empty v-if="!violations.length" description="暂无违约记录"/></article>
  </section>
</template>

<style scoped>
.reservation-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.reservation-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
</style>
