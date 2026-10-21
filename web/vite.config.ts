import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api/v1/system/m1": "http://localhost:8081",
      "/api/v1/system/m2": "http://localhost:8082",
      "/api/v1/auth": "http://localhost:8082",
      "/api/v1/rooms": "http://localhost:8081",
      "/api/v1": "http://localhost:8083",
    },
  },
});
