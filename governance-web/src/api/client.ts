import axios from 'axios'

const timeout = Number(import.meta.env.VITE_GATEWAY_API_TIMEOUT_MS ?? 10_000)

export const gatewayClient = axios.create({
  baseURL: import.meta.env.VITE_GATEWAY_API_BASE_URL ?? 'http://localhost:8080',
  timeout: Number.isFinite(timeout) ? timeout : 10_000,
})

gatewayClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.code === 'ECONNABORTED') {
      error.message = '网关请求超时，请稍后重试。'
    } else if (!error.response) {
      error.message = '无法连接到 API Gateway，请检查服务状态和地址配置。'
    } else if (error.response.status === 401) {
      error.message = '当前会话未认证。正式认证接入前不会发起治理操作。'
    } else if (error.response.status === 403) {
      error.message = '当前会话没有执行该治理操作的权限。'
    }
    return Promise.reject(error)
  },
)
