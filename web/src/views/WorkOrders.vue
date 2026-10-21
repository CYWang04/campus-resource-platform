<script setup lang="ts">
import { ref, onMounted } from "vue";
import { read, command } from "../api";
import { useSession } from "../session";
const session = useSession();
interface Order {
  id: string;
  title: string;
  roomId: string;
  state: string;
  repairerId: string;
  operations?: { id: string; status: string; lastError: string }[];
}
interface Room {
  id: string;
  name: string;
}
const orders = ref<Order[]>([]),
  rooms = ref<Room[]>([]),
  busy = ref(false),
  detail = ref<Order | null>(null);
const form = ref({ roomId: "", title: "", description: "" });
const createKey = ref(crypto.randomUUID());
const actionKeys = new Map<string, string>();
const labels: Record<string, string> = {
  REPORTED: "待开始维修",
  START_PENDING: "正在同步维修限制",
  REPAIRING: "维修中",
  AWAITING_ACCEPTANCE: "待管理员验收",
  RELEASE_PENDING: "正在解除维修限制",
  CLOSED: "已验收关闭",
};
async function load() {
  try {
    orders.value = await read<Order[]>("/work-orders");
    rooms.value = (await read<{ items: Room[] }>("/rooms")).items;
  } catch {}
}
async function submit() {
  busy.value = true;
  try {
    await command("/work-orders", form.value, createKey.value);
    createKey.value = crypto.randomUUID();
    form.value = { roomId: "", title: "", description: "" };
    await load();
  } catch {
  } finally {
    busy.value = false;
  }
}
async function act(o: Order, action: string) {
  busy.value = true;
  const id = o.id + action;
  const key = actionKeys.get(id) ?? crypto.randomUUID();
  actionKeys.set(id, key);
  try {
    await command("/work-orders/" + o.id + "/" + action, {}, key);
    await load();
  } catch {
  } finally {
    busy.value = false;
  }
}
async function inspect(o: Order) {
  try {
    detail.value = await read<Order>("/work-orders/" + o.id);
  } catch {}
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-title">
      <div>
        <span class="eyebrow">M3 · 物业报修</span>
        <h1>让每个问题都有进展</h1>
        <p>维修下架与验收解除通过服务间同步完成。处理中请刷新查询。</p>
      </div>
      <el-button @click="load">刷新进度</el-button>
    </div>
    <article
      v-if="session.user?.permissions.includes('workorders:create')"
      class="panel"
    >
      <h2>提交报修</h2>
      <el-form label-position="top" @submit.prevent="submit"
        ><div class="form-grid">
          <el-form-item label="教室"
            ><el-select v-model="form.roomId" placeholder="选择教室"
              ><el-option
                v-for="r in rooms"
                :key="r.id"
                :value="r.id"
                :label="r.name" /></el-select></el-form-item
          ><el-form-item label="问题标题"
            ><el-input v-model="form.title" maxlength="200"
          /></el-form-item>
        </div>
        <el-form-item label="问题描述"
          ><el-input
            v-model="form.description"
            type="textarea"
            maxlength="2000" /></el-form-item
        ><el-button
          type="primary"
          native-type="submit"
          :disabled="!form.roomId || !form.title || !form.description"
          :loading="busy"
          >提交报修</el-button
        ></el-form
      >
    </article>
    <article class="panel">
      <h2>工单进度</h2>
      <el-table :data="orders"
        ><el-table-column prop="title" label="问题" /><el-table-column
          label="当前状态"
          ><template #default="{ row }"
            ><el-tag>{{ labels[row.state] ?? row.state }}</el-tag></template
          ></el-table-column
        ><el-table-column label="操作" min-width="240"
          ><template #default="{ row }"
            ><el-button text @click="inspect(row)">详情</el-button
            ><el-button
              v-if="
                row.state === 'REPORTED' &&
                session.user?.permissions.includes('workorders:repair')
              "
              text
              :disabled="busy"
              @click="act(row, 'start')"
              >开始维修</el-button
            ><el-button
              v-if="
                row.state === 'REPAIRING' &&
                row.repairerId === session.user?.id
              "
              text
              :disabled="busy"
              @click="act(row, 'complete')"
              >提交完工</el-button
            ><el-button
              v-if="
                row.state === 'AWAITING_ACCEPTANCE' &&
                session.user?.permissions.includes('workorders:accept')
              "
              text
              :disabled="busy"
              @click="act(row, 'accept')"
              >验收通过</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <p class="muted">本版为简化维修链路，派单、评价与超时升级尚未接入。</p>
    </article>
    <el-dialog
      :model-value="!!detail"
      title="同步操作详情"
      @close="detail = null"
      ><template v-if="detail"
        ><p>{{ detail.title }} · {{ labels[detail.state] }}</p>
        <p v-for="op in detail.operations" :key="op.id">
          {{ op.status }} · {{ op.lastError ?? "同步正常" }}<br /><small>{{
            op.id
          }}</small>
        </p></template
      ></el-dialog
    >
  </section>
</template>
