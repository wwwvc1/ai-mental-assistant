import { ElMessage } from 'element-plus'

const lastShown = new Map()

/**
 * 按 key 节流的提示，避免同一功能不可用时的提示刷屏。
 */
export function notifyOnce(key, message, type = 'warning', ttl = 30000) {
  const now = Date.now()
  if (now - (lastShown.get(key) || 0) < ttl) {
    return
  }
  lastShown.set(key, now)
  ElMessage[type](message)
}

export default notifyOnce
