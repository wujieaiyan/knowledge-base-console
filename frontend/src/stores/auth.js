import { defineStore } from 'pinia'
import { authApi } from '../api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('kb_token') || '',
    user: JSON.parse(localStorage.getItem('kb_user') || 'null')
  }),
  getters: {
    loggedIn: (state) => Boolean(state.token),
    displayName: (state) => state.user?.name || state.user?.account || '未登录',
    permissions: (state) => state.user?.permissions || [],
    hasPermission: (state) => (code) =>
      Boolean(state.user?.platformAdmin) || (state.user?.permissions || []).includes(code)
  },
  actions: {
    async login(account, password) {
      const data = await authApi.login({ account, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('kb_token', data.token)
      localStorage.setItem('kb_user', JSON.stringify(data.user))
      return data.user
    },
    async fetchMe() {
      if (!this.token) return null
      try {
        this.user = await authApi.me()
        localStorage.setItem('kb_user', JSON.stringify(this.user))
        return this.user
      } catch (error) {
        this.clear()
        throw error
      }
    },
    async logout() {
      try {
        await authApi.logout()
      } catch (error) {
        // token is stateless; local cleanup is enough
      }
      this.clear()
    },
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem('kb_token')
      localStorage.removeItem('kb_user')
    }
  }
})
