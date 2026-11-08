<template>
  <div class="app-layout">
    <aside class="sidebar">
      <router-link class="brand" to="/series?scope=accessible">
        <div class="brand-mark">
          <el-icon><Collection /></el-icon>
        </div>
        <div>
          <div class="brand-name">知序</div>
          <div class="brand-sub">知识库智能助手</div>
        </div>
      </router-link>

      <nav class="nav">
        <div class="nav-group">
          <div class="nav-label">工作台</div>
          <button class="nav-item" :class="{ active: route.path === '/chat' }" @click="go('/chat')">
            <el-icon><ChatDotRound /></el-icon>
            <span>智能问答</span>
          </button>
        </div>

        <div class="nav-group">
          <div class="nav-label">知识库</div>
          <button
            class="nav-item"
            :class="{ active: isSeriesNav('all') }"
            @click="go('/series?scope=all')"
          >
            <el-icon><Management /></el-icon>
            <span>知识库系统管理</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: isSeriesNav('accessible') }"
            @click="go('/series?scope=accessible')"
          >
            <el-icon><Collection /></el-icon>
            <span>我的可访问系列</span>
          </button>
          <button
            class="nav-item"
            :class="{ active: isSeriesNav('maintainable') }"
            @click="go('/series?scope=maintainable')"
          >
            <el-icon><EditPen /></el-icon>
            <span>我的可维护系列</span>
          </button>
        </div>

        <div v-if="showSystemNav" class="nav-group">
          <div class="nav-label">系统管理</div>
          <button
            v-if="auth.hasPermission('user:read')"
            class="nav-item"
            :class="{ active: route.name === 'admin-users' }"
            @click="go('/admin/users')"
          >
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </button>
          <button
            v-if="auth.hasPermission('department:read')"
            class="nav-item"
            :class="{ active: route.name === 'admin-departments' }"
            @click="go('/admin/departments')"
          >
            <el-icon><OfficeBuilding /></el-icon>
            <span>部门管理</span>
          </button>
          <button
            v-if="auth.hasPermission('role:read')"
            class="nav-item"
            :class="{ active: route.name === 'admin-roles' }"
            @click="go('/admin/roles')"
          >
            <el-icon><Key /></el-icon>
            <span>角色权限</span>
          </button>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="demo-card">
          <div class="demo-card-title">
            <el-icon><MagicStick /></el-icon>
            <span>本地演示环境</span>
          </div>
          <p>本地 Ollama 生成 + Elasticsearch 检索，服务不可用时自动降级。</p>
        </div>

        <el-dropdown trigger="click" @command="handleUserCommand">
          <div class="user-card">
            <div class="avatar">{{ avatarText }}</div>
            <div class="user-info">
              <strong>{{ auth.displayName }}</strong>
              <small>{{ auth.user?.deptName || '未分配部门' }}</small>
            </div>
            <el-icon><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile" disabled>
                {{ auth.user?.account }} · {{ auth.user?.roleName || '普通成员' }}
                {{ auth.user?.platformAdmin ? ' · 全库访问' : '' }}
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <div class="main-wrap">
      <header class="topbar">
        <div class="topbar-left">
          <el-button
            v-if="showBack"
            class="back-btn"
            text
            :icon="ArrowLeft"
            @click="router.push('/series?scope=accessible')"
          />
          <nav class="topbar-crumbs" aria-label="面包屑">
            <template v-for="(crumb, idx) in pageCrumbs" :key="crumb">
              <span class="crumb" :class="{ current: idx === pageCrumbs.length - 1 }">{{ crumb }}</span>
              <span v-if="idx < pageCrumbs.length - 1" class="crumb-sep">/</span>
            </template>
          </nav>
        </div>
        <div class="topbar-right">
          <span class="env-pill">DEMO</span>
          <span class="today">{{ today }}</span>
          <el-tooltip content="查看帮助" placement="bottom">
            <el-button circle text :icon="QuestionFilled" @click="helpVisible = true" />
          </el-tooltip>
        </div>
      </header>

      <main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </main>
    </div>

    <el-dialog v-model="helpVisible" title="使用说明" width="520px">
      <div class="help-list">
        <div class="help-item">
          <strong>新建知识库</strong>
          <p>从知识库列表点击「新建知识库」，依次完成基础信息、解析引擎、分块设置和高级检索设置。</p>
        </div>
        <div class="help-item">
          <strong>导入文档</strong>
          <p>进入知识库详情的「知识管理」，上传 PDF、Word、Excel 或 Markdown 文件，系统会异步完成解析、切片与索引。</p>
        </div>
        <div class="help-item">
          <strong>检索测试</strong>
          <p>在「检索测试」中输入问题，查看混合召回结果、相似度分数与引用来源。</p>
        </div>
        <div class="help-item">
          <strong>运行依赖</strong>
          <p>生成依赖本地 Ollama；检索优先使用 Elasticsearch，不可用时自动回退本地 H2 索引。当前未接入 OCR，扫描件会解析失败。</p>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowLeft, QuestionFilled, MagicStick, User, OfficeBuilding, Key } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const helpVisible = ref(false)

const avatarText = computed(() => (auth.displayName || 'U').slice(0, 1))
const today = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(
  new Date()
)

const showBack = computed(() => route.name === 'series-detail' || route.name === 'series-create')
const showSystemNav = computed(
  () =>
    auth.hasPermission('user:read') ||
    auth.hasPermission('department:read') ||
    auth.hasPermission('role:read')
)

const pageCrumbs = computed(() => {
  if (route.name === 'chat') return ['工作台', '智能问答']
  if (route.name === 'series') return ['知识库', '系列列表']
  if (route.name === 'series-create') return ['知识库', '新建']
  if (route.name === 'series-detail') return ['知识库', '系列详情']
  if (route.name === 'admin-users') return ['系统管理', '用户管理']
  if (route.name === 'admin-departments') return ['系统管理', '部门管理']
  if (route.name === 'admin-roles') return ['系统管理', '角色权限']
  return ['知识库']
})

function isSeriesNav(scope) {
  return route.name === 'series' && (route.query.scope || 'accessible') === scope
}

function go(path) {
  router.push(path)
}

async function handleUserCommand(command) {
  if (command !== 'logout') return
  try {
    await ElMessageBox.confirm('确认退出当前账号？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await auth.logout()
    router.replace('/login')
  } catch (error) {
    // canceled
  }
}
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 236px minmax(0, 1fr);
  background: var(--kb-bg);
}

.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: 22px 14px 16px;
  background: #181e1a;
  color: #e8e6dc;
  border-right: 1px solid rgba(255, 255, 255, 0.05);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 2px 8px 20px;
  margin-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}

.brand-mark {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--kb-primary);
  color: #f2f0e6;
  font-size: 18px;
  box-shadow: inset 0 0 0 1px rgba(169, 119, 63, 0.45);
}

.brand-name {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 4px;
  color: #f2f0e7;
}

.brand-sub {
  margin-top: 3px;
  font-size: 9.5px;
  letter-spacing: 1.6px;
  color: rgba(232, 230, 220, 0.46);
}

.nav {
  flex: 1;
  padding-top: 6px;
  overflow-y: auto;
}

.nav-group {
  margin-bottom: 16px;
}

.nav-label {
  font-size: 9.5px;
  letter-spacing: 2px;
  color: rgba(232, 230, 220, 0.34);
  padding: 10px 12px 7px;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 11px;
  margin-bottom: 3px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: rgba(232, 230, 220, 0.7);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
  text-align: left;
}

.nav-item:hover {
  color: #f4f2e9;
  background: rgba(255, 255, 255, 0.055);
}

.nav-item.active {
  color: #f7f5ec;
  background: rgba(31, 111, 92, 0.24);
  box-shadow: inset 2px 0 0 var(--kb-accent);
}

.nav-item .el-icon {
  font-size: 15px;
}

.sidebar-foot {
  padding-top: 14px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.demo-card {
  padding: 12px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.055);
  margin-bottom: 10px;
}

.demo-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: #c8a877;
  letter-spacing: 0.6px;
}

.demo-card p {
  margin: 7px 0 0;
  font-size: 10px;
  line-height: 1.7;
  color: rgba(232, 230, 220, 0.42);
}

.user-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 9px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.16s ease;
}

.user-card:hover {
  background: rgba(255, 255, 255, 0.055);
}

.avatar {
  width: 33px;
  height: 33px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #26332b;
  border: 1px solid rgba(169, 119, 63, 0.35);
  color: #ddc9a7;
  font-weight: 600;
  font-size: 13px;
}

.user-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-info strong {
  font-size: 12px;
  font-weight: 500;
  color: #f0eee5;
}

.user-info small {
  font-size: 10px;
  color: rgba(232, 230, 220, 0.44);
}

.main-wrap {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  height: 64px;
  padding: 0 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--kb-surface);
  border-bottom: 1px solid var(--kb-line);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.back-btn {
  margin-right: 2px;
}

.topbar-crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  letter-spacing: 0.2px;
  color: var(--kb-muted);
  white-space: nowrap;
  overflow: hidden;
}

.crumb.current {
  color: var(--kb-ink-soft);
  font-weight: 500;
}

.crumb-sep {
  color: var(--kb-line-strong);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.env-pill {
  font-size: 9px;
  letter-spacing: 1.8px;
  padding: 3px 8px;
  border-radius: 3px;
  background: var(--kb-accent-soft);
  border: 1px solid #e7d9c1;
  color: #8c6132;
  font-weight: 600;
}

.today {
  font-size: 12px;
  color: var(--kb-muted);
}

.main {
  flex: 1;
  min-width: 0;
  background: var(--kb-bg);
}

.help-list {
  display: grid;
  gap: 14px;
}

.help-item strong {
  font-size: 13px;
}

.help-item p {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--kb-muted);
  line-height: 1.8;
}

@media (max-width: 900px) {
  .app-layout {
    grid-template-columns: 76px minmax(0, 1fr);
  }

  .brand {
    justify-content: center;
    padding: 4px 0 18px;
  }

  .brand > div:last-child,
  .nav-label,
  .nav-item span,
  .sidebar-foot {
    display: none;
  }

  .nav-item {
    justify-content: center;
  }

  .topbar {
    padding: 0 16px;
  }
}

@media (max-width: 720px) {
  .today {
    display: none;
  }

  .topbar-right {
    gap: 10px;
  }
}
</style>
