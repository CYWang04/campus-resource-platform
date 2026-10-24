import axios from "axios";
import { ElMessage } from "element-plus";
export const api = axios.create({ baseURL: "/api/v1", timeout: 10000 });
let token = "";
export const setToken = (value: string) => {
  token = value;
};
api.interceptors.request.use((config) => {
  if (token) config.headers.Authorization = "Bearer " + token;
  return config;
});
api.interceptors.response.use(
  (r) => r,
  (e) => {
    ElMessage.error(
      e.response?.data?.message ?? "请求暂未完成，请刷新查询结果后再重试",
    );
    return Promise.reject(e);
  },
);
export async function read<T>(path: string): Promise<T> {
  return (await api.get(path)).data.data;
}
export async function command<T>(
  path: string,
  body: unknown,
  key: string,
): Promise<T> {
  return (await api.post(path, body, { headers: { "Idempotency-Key": key } }))
    .data.data;
}
