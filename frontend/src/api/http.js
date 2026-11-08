import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('kb_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.code && body.code !== '0') {
      if (!response.config.silent) {
        ElMessage.error(body.message || '请求失败')
      }
      return Promise.reject(body)
    }
    return body ? body.data : null
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data || {}
    if (status === 401) {
      localStorage.removeItem('kb_token')
      localStorage.removeItem('kb_user')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    }
    if (!error.config?.silent) {
      ElMessage.error(body.message || (status ? `请求失败 (${status})` : '网络异常，请检查后端服务'))
    }
    return Promise.reject({ ...body, status, message: body.message || '请求失败' })
  }
)

export default http
