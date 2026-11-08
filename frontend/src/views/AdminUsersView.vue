<template>
  <div class="page-shell">
    <div class="page-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">维护账号、所属部门与全局角色。用户只能看到自己创建的会话。</p>
      </div>
      <el-button v-if="canManage" type="primary" :icon="Plus" size="large" @click="openCreate">
        新增用户
      </el-button>
    </div>

    <div class="toolbar surface">
      <el-input
        v-model="filters.keyword"
        class="search-input"
        placeholder="搜索姓名或账号"
        clearable
        :prefix-icon="Search"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filters.deptId" placeholder="全部部门" clearable class="filter-select" @change="reload">
        <el-option v-for="dept in deptOptions" :key="dept.id" :label="dept.label" :value="dept.id" />
      </el-select>
      <el-select v-model="filters.active" placeholder="全部状态" clearable class="filter-select" @change="reload">
        <el-option label="启用" :value="true" />
        <el-option label="停用" :value="false" />
      </el-select>
      <div class="toolbar-right">
        <span class="count">共 {{ total }} 个账号</span>
        <el-button :icon="Refresh" circle plain :loading="loading" @click="load" />
      </div>
    </div>

    <div class="surface table-shell">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="用户" min-width="190">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="avatar">{{ row.name.slice(0, 1) }}</span>
              <div>
                <strong>{{ row.name }}</strong>
                <small>{{ row.account }}</small>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="部门" min-width="130">
          <template #default="{ row }">{{ row.deptName || '未分配' }}</template>
        </el-table-column>
        <el-table-column label="角色" min-width="130">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ row.roleName || row.roleCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="全库访问" width="100">
          <template #default="{ row }">
            <span :class="row.platformAdmin ? 'flag-on' : 'muted'">{{ row.platformAdmin ? '是' : '否' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small" effect="light">
              {{ row.active ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近登录" width="160">
          <template #default="{ row }">
            <span class="muted mono">{{ formatTime(row.lastLoginAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link @click="openReset(row)">重置密码</el-button>
            <el-button
              link
              :type="row.active ? 'danger' : 'success'"
              :disabled="row.id === auth.user?.id"
              @click="toggleActive(row)"
            >
              {{ row.active ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑用户' : '新增用户'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="86px">
        <el-form-item label="账号" prop="account">
          <el-input v-model="form.account" :disabled="Boolean(form.id)" placeholder="3-32 位小写字母、数字或 _ . -" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="6-72 个字符" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" placeholder="2-32 个字符" />
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="form.deptId" placeholder="未分配" clearable class="full">
            <el-option v-for="dept in deptOptions" :key="dept.id" :label="dept.label" :value="dept.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色" prop="roleCode">
          <el-select v-model="form.roleCode" class="full" :disabled="isSelf">
            <el-option
              v-for="role in roles"
              :key="role.code"
              :label="role.name"
              :value="role.code"
              :disabled="role.code === 'ADMIN' && !auth.user?.platformAdmin"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.id" label="状态">
          <el-switch v-model="form.active" active-text="启用" inactive-text="停用" :disabled="isSelf" />
        </el-form-item>
        <el-form-item v-if="auth.user?.platformAdmin" label="全库访问">
          <el-switch v-model="form.platformAdmin" :disabled="isSelf" />
          <span class="form-hint">可跨知识库访问全部数据，仅平台管理员可授予</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" title="重置密码" width="420px">
      <el-form label-width="80px">
        <el-form-item label="账号">
          <span class="mono">{{ resetTarget?.account }}</span>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="resetPassword" type="password" show-password placeholder="6-72 个字符" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const departments = ref([])
const roles = ref([])
const filters = reactive({ keyword: '', deptId: null, active: null })

const canManage = computed(() => auth.hasPermission('user:manage'))

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, account: '', password: '', name: '', deptId: null, roleCode: 'MEMBER', active: true, platformAdmin: false })
const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入初始密码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roleCode: [{ required: true, message: '请选择角色', trigger: 'change' }]
}
const isSelf = computed(() => form.id && form.id === auth.user?.id)

const resetVisible = ref(false)
const resetTarget = ref(null)
const resetPassword = ref('')

const deptOptions = computed(() => {
  const map = new Map(departments.value.map((dept) => [dept.id, { ...dept, children: [] }]))
  const roots = []
  map.forEach((dept) => {
    if (dept.parentId && map.has(dept.parentId)) map.get(dept.parentId).children.push(dept)
    else roots.push(dept)
  })
  const result = []
  const walk = (nodes, depth) => {
    nodes
      .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id)
      .forEach((node) => {
        result.push({ id: node.id, label: `${'　'.repeat(depth)}${depth ? '└ ' : ''}${node.name}` })
        walk(node.children, depth + 1)
      })
  }
  walk(roots, 0)
  return result
})

async function load() {
  loading.value = true
  try {
    const data = await adminApi.users({
      keyword: filters.keyword || undefined,
      deptId: filters.deptId || undefined,
      active: filters.active === null ? undefined : filters.active,
      page: page.value,
      size: pageSize
    })
    rows.value = data.items || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  const [deptResult, roleResult] = await Promise.allSettled([adminApi.departments(), adminApi.roles()])
  departments.value = deptResult.status === 'fulfilled' ? deptResult.value : []
  roles.value = roleResult.status === 'fulfilled' ? roleResult.value : []
}

function reload() {
  page.value = 1
  load()
}

function openCreate() {
  Object.assign(form, {
    id: null,
    account: '',
    password: '',
    name: '',
    deptId: null,
    roleCode: 'MEMBER',
    active: true,
    platformAdmin: false
  })
  formVisible.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    account: row.account,
    password: '',
    name: row.name,
    deptId: row.deptId,
    roleCode: row.roleCode,
    active: row.active,
    platformAdmin: row.platformAdmin
  })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch (error) {
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await adminApi.updateUser(form.id, {
        name: form.name,
        deptId: form.deptId,
        roleCode: form.roleCode,
        active: form.active,
        platformAdmin: auth.user?.platformAdmin ? form.platformAdmin : undefined
      })
      ElMessage.success('用户已更新')
    } else {
      await adminApi.createUser({
        account: form.account,
        password: form.password,
        name: form.name,
        deptId: form.deptId,
        roleCode: form.roleCode,
        platformAdmin: auth.user?.platformAdmin ? form.platformAdmin : false
      })
      ElMessage.success('用户已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleActive(row) {
  const action = row.active ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(`确认${action}账号「${row.name}」？`, `${action}用户`, {
      confirmButtonText: action,
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (error) {
    return
  }
  await adminApi.updateUser(row.id, {
    name: row.name,
    deptId: row.deptId,
    roleCode: row.roleCode,
    active: !row.active,
    platformAdmin: auth.user?.platformAdmin ? row.platformAdmin : undefined
  })
  ElMessage.success(`已${action}`)
  await load()
}

function openReset(row) {
  resetTarget.value = row
  resetPassword.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetPassword.value || resetPassword.value.length < 6) {
    ElMessage.warning('密码至少 6 个字符')
    return
  }
  saving.value = true
  try {
    await adminApi.resetPassword(resetTarget.value.id, resetPassword.value)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } finally {
    saving.value = false
  }
}

function formatTime(value) {
  if (!value) return '从未登录'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(value))
}

onMounted(() => {
  loadOptions()
  load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 18px;
}

.search-input {
  width: 260px;
}

.filter-select {
  width: 150px;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.count {
  font-size: 11px;
  color: var(--kb-muted);
}

.table-shell {
  padding: 4px 8px 8px;
  overflow: hidden;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 32px;
  height: 32px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-weight: 600;
  flex-shrink: 0;
}

.user-cell strong {
  display: block;
  font-size: 13px;
  font-weight: 500;
}

.user-cell small {
  display: block;
  margin-top: 2px;
  font-size: 10px;
  color: var(--kb-muted);
}

.flag-on {
  color: var(--kb-primary);
  font-weight: 500;
}

.full {
  width: 100%;
}

.form-hint {
  margin-left: 10px;
  font-size: 11px;
  color: var(--kb-muted);
}

.pager {
  display: flex;
  justify-content: center;
  padding: 14px 0 6px;
}

@media (max-width: 760px) {
  .toolbar {
    flex-wrap: wrap;
  }

  .search-input,
  .filter-select {
    width: 100%;
  }

  .toolbar-right {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
