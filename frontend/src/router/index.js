import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    redirect: '/series?scope=accessible',
    children: [
      {
        path: 'chat',
        name: 'chat',
        component: () => import('../views/ChatView.vue'),
        meta: { title: '新会话', subtitle: '基于知识库的检索问答' }
      },
      {
        path: 'series',
        name: 'series',
        component: () => import('../views/SeriesListView.vue'),
        meta: { title: '知识库', subtitle: '浏览、管理可访问的知识库' }
      },
      {
        path: 'series/create',
        name: 'series-create',
        component: () => import('../views/SeriesCreateView.vue'),
        meta: { title: '新建知识库', subtitle: '按步骤完成解析、切片与检索配置' }
      },
      {
        path: 'series/:id',
        name: 'series-detail',
        component: () => import('../views/SeriesDetailView.vue'),
        meta: { title: '知识库详情', subtitle: '' }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('../views/AdminUsersView.vue'),
        meta: { title: '用户管理', permission: 'user:read' }
      },
      {
        path: 'admin/departments',
        name: 'admin-departments',
        component: () => import('../views/AdminDepartmentsView.vue'),
        meta: { title: '部门管理', permission: 'department:read' }
      },
      {
        path: 'admin/roles',
        name: 'admin-roles',
        component: () => import('../views/AdminRolesView.vue'),
        meta: { title: '角色权限', permission: 'role:read' }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/series?scope=accessible' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) {
    if (auth.loggedIn) return '/series?scope=accessible'
    return true
  }
  if (!auth.loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (!auth.user || !Array.isArray(auth.user.permissions)) {
    try {
      await auth.fetchMe()
    } catch (error) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return { path: '/series', query: { scope: 'accessible' } }
  }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title || '知识库'} · 知序`
})

export default router
