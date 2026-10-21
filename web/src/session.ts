import { defineStore } from "pinia";
import { ref } from "vue";
import { api, setToken } from "./api";
export interface User {
  id: string;
  username: string;
  displayName: string;
  departmentCode: string;
  permissions: string[];
}
export const useSession = defineStore("session", () => {
  const user = ref<User | null>(null);
  async function login(username: string, password: string) {
    const { data } = await api.post("/auth/login", { username, password });
    setToken(data.data.token);
    user.value = data.data.user;
  }
  async function logout() {
    try {
      await api.post("/auth/logout");
    } finally {
      setToken("");
      user.value = null;
    }
  }
  return { user, login, logout };
});
