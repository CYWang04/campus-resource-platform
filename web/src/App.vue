<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import { useSession } from "./session";
const session = useSession();
const route = useRoute();
const username = ref("student"),
  password = ref(""),
  busy = ref(false);
const pageNames: Record<string, string> = {
  "/": "学习空间",
  "/reservations": "座位预约",
  "/m1-admin": "资源管理",
  "/applications": "教室申请",
  "/work-orders": "物业报修",
  "/notifications": "通知中心",
};
const pageName = computed(() => pageNames[route.path] ?? "校园服务");
async function login() {
  busy.value = true;
  try {
    await session.login(username.value, password.value);
    password.value = "";
  } catch {
    /* Request interceptor presents errors. */
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div v-if="!session.user" class="login">
    <div class="login-intro">
      <a class="wordmark" href="/"
        >CAMPUS<br />RESOURCE<span>校园资源服务平台</span></a
      >
      <div class="login-editorial">
        <span class="eyebrow">SPACE FOR YOUR EVERYDAY</span>
        <h1>让空间，<br />留给好想法。</h1>
        <p>
          从一个自习座位，到一次小组讨论。<br />学习、申请与报修，在这里有序发生。
        </p>
      </div>
      <div class="login-index">
        <span>01 / 学习空间</span><span>02 / 教室申请</span
        ><span>03 / 物业报修</span>
      </div>
    </div>
    <div class="login-form-area">
      <div class="login-card">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>登录校园服务</h2>
        <p>从这里，安排你的校园日常。</p>
        <el-form @submit.prevent="login" label-position="top">
          <el-form-item label="账号"
            ><el-input
              v-model="username"
              autocomplete="username"
              placeholder="输入账号"
          /></el-form-item>
          <el-form-item label="密码"
            ><el-input
              v-model="password"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="输入密码"
          /></el-form-item>
          <el-button
            class="login-submit"
            native-type="submit"
            type="primary"
            :loading="busy"
            :disabled="!username.trim() || !password"
            >登录 <span aria-hidden="true">↗</span></el-button
          >
        </el-form>
        <details class="demo-help">
          <summary>演示账号与使用说明</summary>
          <p>
            账号：student / technician / manager<br />共同测试密码：CampusDemo2026!<br />为保护会话，刷新页面后需重新登录。
          </p>
        </details>
      </div>
      <small class="login-footer">复旦大学 · 校园资源服务平台</small>
    </div>
  </div>
  <div v-else class="shell">
    <aside class="app-sidebar">
      <router-link class="wordmark" to="/"
        >CAMPUS<br />RESOURCE<span>校园资源服务平台</span></router-link
      >
      <span class="nav-caption">探索与办理 / SERVICES</span>
      <nav aria-label="主要导航">
        <router-link to="/"
          ><span class="nav-number">01</span>学习空间<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
        <router-link
          v-if="session.user.permissions.includes('reservations:read')"
          to="/reservations"
          ><span class="nav-number">02</span>座位预约<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
        <router-link to="/applications"
          ><span class="nav-number">03</span>教室申请<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
        <router-link to="/work-orders"
          ><span class="nav-number">04</span>物业报修<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
        <router-link to="/notifications"
          ><span class="nav-number">05</span>通知中心<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
        <router-link
          v-if="session.user.permissions.includes('resources:manage')"
          to="/m1-admin"
          ><span class="nav-number">06</span>资源管理<span
            class="nav-arrow"
            aria-hidden="true"
            >↗</span
          ></router-link
        >
      </nav>
      <div class="aside-note">
        让校园事务井然有序。<br /><span>CAMPUS RESOURCE PLATFORM</span>
      </div>
    </aside>
    <main class="app-main">
      <header class="app-header">
        <div class="breadcrumb">
          <span>校园服务</span><span aria-hidden="true">/</span
          ><strong>{{ pageName }}</strong>
        </div>
        <div class="user-menu">
          <el-button text @click="session.logout">退出 ↗</el-button>
        </div>
      </header>
      <router-view :key="session.user.id" />
      <footer class="app-footer">
        <span>CAMPUS RESOURCE</span><span>学习 · 申请 · 维护</span>
      </footer>
    </main>
  </div>
</template>
