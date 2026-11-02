import axios from 'axios'
import { ElMessage } from 'element-plus'

const BASE_URL = '/api'
const TOKEN_KEY = 'token'
const REFRESH_TOKEN_KEY = 'refreshToken'
const USER_INFO_KEY = 'userInfo'

// 这些接口本身就是认证入口，401 时不能再触发刷新逻辑
const AUTH_URLS = ['/user/login', '/user/refresh', '/user/add']

// 创建axios实例
const service = axios.create({
  baseURL: BASE_URL,
  timeout: 5000,
})

let isRefreshing = false
let pendingRequests = []
let redirecting = false

export const getAccessToken = () => localStorage.getItem(TOKEN_KEY)
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY)

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_INFO_KEY)
}

export function redirectToLogin(message = '登录已过期，请重新登录') {
  clearAuth()
  if (redirecting || window.location.pathname === '/auth/login') {
    return
  }
  redirecting = true
  ElMessage.error(message)
  setTimeout(() => {
    window.location.href = '/auth/login'
  }, 300)
}

/**
 * 用刷新令牌换取新的 access token。
 * 后端业务失败也是 HTTP 200 + BUSINESS_ERROR，因此必须检查响应体 code。
 */
export async function refreshAccessToken() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('NO_REFRESH_TOKEN')
  }
  const response = await axios.post(
    `${BASE_URL}/user/refresh`,
    { refreshToken },
    { timeout: 10000 }
  )
  const body = response.data
  if (!body || String(body.code) !== '200' || !body.data?.token) {
    throw new Error(body?.msg || '刷新登录状态失败')
  }
  localStorage.setItem(TOKEN_KEY, body.data.token)
  if (body.data.refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, body.data.refreshToken)
  }
  return body.data.token
}

// 请求拦截器
service.interceptors.request.use(
  (config) => {
    const token = getAccessToken()
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

function resolveFallback(config, error) {
  if (!config || !Object.prototype.hasOwnProperty.call(config, '_fallback')) {
    return undefined
  }
  const fallback = config._fallback
  const value = typeof fallback === 'function' ? fallback(error) : fallback
  return Promise.resolve(value)
}

// 响应拦截器
service.interceptors.response.use(
  (response) => {
    const { data, config } = response

    if (config?.responseType === 'blob') {
      return response
    }

    // 统一处理业务响应包 { code, msg, data }
    if (data && typeof data === 'object' && Object.prototype.hasOwnProperty.call(data, 'code')) {
      if (String(data.code) === '200') {
        return data.data
      }
      const message = data.msg || '请求失败'
      ElMessage.error(message)
      const error = new Error(message)
      error.code = data.code
      error.response = response
      // 标记拦截器已经提示过，避免调用方重复弹窗
      error._notified = true
      return Promise.reject(error)
    }

    return data
  },
  (error) => {
    const { response, config } = error
    const status = response?.status

    // 1) 显式降级：缺失接口（未登录时可能是 401 而不是 404），吞掉错误返回兜底值
    const fallback = resolveFallback(config, error)
    if (fallback !== undefined) {
      return fallback
    }

    // 2) 401：单飞刷新后重试原请求一次
    if (status === 401 && !config?._retry && !AUTH_URLS.some((url) => (config?.url || '').includes(url))) {
      config._retry = true

      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          pendingRequests.push({ config, resolve, reject })
        })
      }

      isRefreshing = true
      return refreshAccessToken()
        .then(() => {
          const queue = pendingRequests
          pendingRequests = []
          queue.forEach((item) => {
            service(item.config).then(item.resolve).catch(item.reject)
          })
          // 重新走请求拦截器，自动带上新的 access token
          return service(config)
        })
        .catch((refreshError) => {
          const queue = pendingRequests
          pendingRequests = []
          queue.forEach((item) => item.reject(refreshError))
          redirectToLogin()
          return Promise.reject(refreshError)
        })
        .finally(() => {
          isRefreshing = false
        })
    }

    // 3) 401 且无法刷新（没有 refresh token / 已重试过）
    if (status === 401) {
      redirectToLogin()
      return Promise.reject(error)
    }

    // 4) 403：账号被禁用或无权限，刷新无意义
    if (status === 403) {
      redirectToLogin(response?.data?.msg || '账号无权访问，请重新登录')
      return Promise.reject(error)
    }

    return Promise.reject(error)
  }
)

export default service
