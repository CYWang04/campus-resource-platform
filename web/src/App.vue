<script setup lang="ts">
import { ref } from "vue";
import { useSession } from "./session";
const session = useSession();
const username = ref("student"),
  password = ref(""),
  busy = ref(false);
async function login() {
  busy.value = true;
  try {
    await session.login(username.value, password.value);
    password.value = "";
  } catch {
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div v-if="!session.user" class="login">
    <div class="login-card">
      <span class="eyebrow">FUDAN · CAMPUS SERVICES</span>
      <h1>校园资源服务平台</h1>
      <p>连接学习空间，让报修处理有迹可循。</p>
      <el-form @submit.prevent="login" label-position="top"
        ><el-form-item label="演示账号"
          ><el-input v-model="username" autocomplete="username" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password" /></el-form-item
        ><el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          style="width: 100%"
          >登录</el-button
        ></el-form
      ><small
        >账号：student / technician / manager；共同测试密码：CampusDemo2026!。刷新页面后需重新登录。</small
      >
    </div>
  </div>
  <div v-else class="shell">
    <aside>
      <div class="brand">校园资源<span>Campus Resource Platform</span></div>
      <nav>
        <router-link to="/">学习空间</router-link
        ><router-link
          v-if="session.user.permissions.includes('reservations:read')"
          to="/reservations"
          >座位预约</router-link
        ><router-link v-if="session.user.permissions.includes('resources:manage')" to="/m1-admin">资源管理</router-link
        ><router-link to="/applications">教室申请</router-link
        ><router-link to="/work-orders">物业报修</router-link
        ><router-link to="/notifications">通知中心</router-link>
      </nav>
      <div class="aside-note">公共基础开发版<br />维修链路优先验收</div>
    </aside>
    <main>
      <header>
        <span>复旦大学 · 校园服务</span>
        <div>
          {{ session.user.displayName }}
          <el-button text @click="session.logout">退出</el-button>
        </div>
      </header>
      <router-view :key="session.user.id" />
    </main>
  </div>
</template>
