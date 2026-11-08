import http from './http'

export const authApi = {
  login: (data) => http.post('/auth/login', data, { silent: true }),
  me: () => http.get('/auth/me', { silent: true }),
  logout: () => http.post('/auth/logout', {}, { silent: true })
}

export const orgApi = {
  departments: () => http.get('/org/departments/tree'),
  users: (params) => http.get('/org/users', { params }),
  departmentMembers: (deptId) => http.get(`/org/departments/${deptId}/members`)
}

export const seriesApi = {
  list: (params) => http.get('/kb/series', { params }),
  create: (data) => http.post('/kb/series', data),
  detail: (id) => http.get(`/kb/series/${id}`),
  update: (id, data) => http.patch(`/kb/series/${id}`, data),
  disable: (id) => http.post(`/kb/series/${id}/disable`),
  enable: (id) => http.post(`/kb/series/${id}/enable`),
  remove: (id) => http.delete(`/kb/series/${id}`),
  stats: (id) => http.get(`/kb/series/${id}/stats`)
}

export const configApi = {
  current: (seriesId) => http.get(`/kb/series/${seriesId}/config`),
  versions: (seriesId) => http.get(`/kb/series/${seriesId}/config-versions`),
  version: (seriesId, versionId) => http.get(`/kb/series/${seriesId}/config-versions/${versionId}`),
  save: (seriesId, data) => http.post(`/kb/series/${seriesId}/config-versions`, data)
}

export const memberApi = {
  list: (seriesId) => http.get(`/kb/series/${seriesId}/members`),
  grant: (seriesId, grants) => http.post(`/kb/series/${seriesId}/members`, { grants }),
  updateRole: (seriesId, memberId, role) =>
    http.patch(`/kb/series/${seriesId}/members/${memberId}`, { role }),
  remove: (seriesId, memberId) => http.delete(`/kb/series/${seriesId}/members/${memberId}`)
}

export const directoryApi = {
  tree: (seriesId) => http.get(`/kb/series/${seriesId}/directories/tree`),
  create: (seriesId, data) => http.post(`/kb/series/${seriesId}/directories`, data),
  rename: (seriesId, id, name) => http.patch(`/kb/series/${seriesId}/directories/${id}`, { name }),
  move: (seriesId, id, targetParentId) =>
    http.post(`/kb/series/${seriesId}/directories/${id}/move`, { targetParentId }),
  remove: (seriesId, id, params) =>
    http.delete(`/kb/series/${seriesId}/directories/${id}`, { params })
}

export const tagApi = {
  list: (seriesId) => http.get(`/kb/series/${seriesId}/tags`),
  create: (seriesId, data) => http.post(`/kb/series/${seriesId}/tags`, data),
  update: (seriesId, id, data) => http.patch(`/kb/series/${seriesId}/tags/${id}`, data),
  remove: (seriesId, id) => http.delete(`/kb/series/${seriesId}/tags/${id}`)
}

export const documentApi = {
  list: (seriesId, params) => http.get(`/kb/series/${seriesId}/documents`, { params }),
  upload: (seriesId, formData) =>
    http.post(`/kb/series/${seriesId}/documents`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }),
  statusSummary: (seriesId) => http.get(`/kb/series/${seriesId}/documents/status-summary`),
  detail: (seriesId, id) => http.get(`/kb/series/${seriesId}/documents/${id}`),
  update: (seriesId, id, data) => http.patch(`/kb/series/${seriesId}/documents/${id}`, data),
  remove: (seriesId, id) => http.delete(`/kb/series/${seriesId}/documents/${id}`),
  reparse: (seriesId, id) => http.post(`/kb/series/${seriesId}/documents/${id}/reparse`),
  disable: (seriesId, id) => http.post(`/kb/series/${seriesId}/documents/${id}/disable`),
  enable: (seriesId, id) => http.post(`/kb/series/${seriesId}/documents/${id}/enable`),
  chunks: (seriesId, id, params) =>
    http.get(`/kb/series/${seriesId}/documents/${id}/chunks`, { params }),
  tags: (seriesId, id) => http.get(`/kb/series/${seriesId}/documents/${id}/tags`),
  setTags: (seriesId, id, tagIds) =>
    http.put(`/kb/series/${seriesId}/documents/${id}/tags`, { tagIds }),
  batch: (seriesId, data) => http.post(`/kb/series/${seriesId}/documents/batch`, data)
}

export const taskApi = {
  list: (seriesId, params) => http.get(`/kb/series/${seriesId}/tasks`, { params }),
  detail: (seriesId, id) => http.get(`/kb/series/${seriesId}/tasks/${id}`),
  logs: (seriesId, id) => http.get(`/kb/series/${seriesId}/tasks/${id}/logs`),
  retry: (seriesId, id) => http.post(`/kb/series/${seriesId}/tasks/${id}/retry`),
  cancel: (seriesId, id) => http.post(`/kb/series/${seriesId}/tasks/${id}/cancel`)
}

export const retrievalApi = {
  test: (seriesId, data) => http.post(`/kb/series/${seriesId}/retrieval/test`, data)
}

export const chatApi = {
  sessions: () => http.get('/chat/sessions'),
  createSession: (seriesId) => http.post('/chat/sessions', { seriesId }),
  renameSession: (id, title) => http.put(`/chat/sessions/${id}`, { title }),
  deleteSession: (id) => http.delete(`/chat/sessions/${id}`),
  messages: (id) => http.get(`/chat/sessions/${id}/messages`),
  send: (id, content) => http.post(`/chat/sessions/${id}/messages`, { content })
}

export const adminApi = {
  users: (params) => http.get('/admin/users', { params }),
  createUser: (data) => http.post('/admin/users', data),
  updateUser: (id, data) => http.put(`/admin/users/${id}`, data),
  resetPassword: (id, password) => http.put(`/admin/users/${id}/password`, { password }),
  departments: () => http.get('/admin/departments'),
  createDepartment: (data) => http.post('/admin/departments', data),
  updateDepartment: (id, data) => http.put(`/admin/departments/${id}`, data),
  setDepartmentStatus: (id, active) => http.patch(`/admin/departments/${id}/status`, { active }),
  deleteDepartment: (id) => http.delete(`/admin/departments/${id}`),
  roles: () => http.get('/admin/roles'),
  permissions: () => http.get('/admin/permissions'),
  createRole: (data) => http.post('/admin/roles', data),
  updateRole: (id, data) => http.put(`/admin/roles/${id}`, data),
  deleteRole: (id) => http.delete(`/admin/roles/${id}`)
}
