import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import { createRouter, createWebHistory } from "vue-router";
import App from "./App.vue";
import Rooms from "./views/Rooms.vue";
import WorkOrders from "./views/WorkOrders.vue";
import Applications from "./views/Applications.vue";
import Notices from "./views/Notices.vue";
import Reservations from "./views/Reservations.vue";
import M1Admin from "./views/M1Admin.vue";
import "./style.css";
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: Rooms },
    { path: "/reservations", component: Reservations },
    { path: "/m1-admin", component: M1Admin },
    { path: "/applications", component: Applications },
    { path: "/work-orders", component: WorkOrders },
    { path: "/notifications", component: Notices },
  ],
});
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount("#app");
