import axios from 'axios'

const timeout = Number(import.meta.env.VITE_GATEWAY_API_TIMEOUT_MS ?? 10_000)

export const gatewayClient = axios.create({
  baseURL: import.meta.env.VITE_GATEWAY_API_BASE_URL ?? '',
  timeout: Number.isFinite(timeout) ? timeout : 10_000,
  withCredentials: true,
})

gatewayClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.code === 'ECONNABORTED') {
      error.message = '网关请求超时，请稍后重试。'
    } else if (!error.response) {
      error.message = '无法连接到 API Gateway，请检查服务状态和同源代理配置。'
    } else if (error.response.status === 401) {
      error.message = '当前管理员会话未认证或已失效。'
    } else if (error.response.status === 403) {
      error.message = '当前会话没有执行该治理操作的权限。'
    }
    return Promise.reject(error)
  },
)
