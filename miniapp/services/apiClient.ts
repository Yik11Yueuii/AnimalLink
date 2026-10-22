export type QueryValue = string | number | boolean | null | undefined
export type Query = Record<string, QueryValue>

export class ApiRequestError extends Error {
  constructor(message: string, public readonly statusCode: number | null) {
    super(message)
    this.name = 'ApiRequestError'
  }
}

const normalizeBaseUrl = (baseUrl: string) => baseUrl.trim().replace(/\/+$/, '')

export const buildGatewayUrl = (baseUrl: string, path: string, query: Query = {}): string => {
  const base = normalizeBaseUrl(baseUrl)
  if (!base) throw new ApiRequestError('尚未配置 Gateway Base URL。', null)
  if (!/^https?:\/\//i.test(base)) throw new ApiRequestError('Gateway Base URL 必须使用 http:// 或 https://。', null)
  const search = Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&')
  return `${base}${path.startsWith('/') ? path : `/${path}`}${search ? `?${search}` : ''}`
}

export type JsonRequester = <T>(path: string, query?: Query) => Promise<T>

export const createJsonRequester = (baseUrl: string): JsonRequester => <T>(path: string, query: Query = {}) => new Promise<T>((resolve, reject) => {
  wx.request({
    url: buildGatewayUrl(baseUrl, path, query),
    method: 'GET',
    timeout: 8000,
    header: { Accept: 'application/json' },
    success(response) {
      if (response.statusCode >= 200 && response.statusCode < 300) {
        resolve(response.data as T)
        return
      }
      reject(new ApiRequestError(`公共接口返回 ${response.statusCode}。`, response.statusCode))
    },
    fail(error) {
      reject(new ApiRequestError(error.errMsg || '网络请求失败。', null))
    },
  })
})
