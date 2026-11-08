<template>
  <div class="page-shell">
    <div class="page-head">
      <div>
        <h1 class="page-title">角色权限</h1>
        <p class="page-subtitle">全局角色控制系统管理与会话能力；知识库范围仍由各知识库成员角色决定。</p>
      </div>
      <el-button v-if="canManage" type="primary" :icon="Plus" size="large" @click="openCreate">
        新增角色
      </el-button>
    </div>

    <div class="role-grid" v-loading="loading">
      <div v-for="role in roles" :key="role.id" class="role-card surface">
        <div class="role-head">
          <div>
            <div class="role-name">
              {{ role.name }}
              <el-tag v-if="role.builtin" size="small" effect="plain" type="warning">内置</el-tag>
            </div>
            <span class="role-code mono">{{ role.code }}</span>
          </div>
          <span class="role-users">{{ role.userCount }} 人</span>
        </div>
        <p class="role-desc">{{ role.description || '未填写角色说明' }}</p>
        <div class="role-perms">
          <el-tag
            v-for="code in role.permissions"
            :key="code"
            size="small"
            effect="plain"
            class="perm-tag"
          >
            {{ permissionLabel(code) }}
          </el-tag>
          <span v-if="!role.permissions.length" class="muted empty-perm">无任何权限</span>
        </div>
        <div v-if="canManage && !role.builtin" class="role-actions">
          <el-button link type="primary" @click="openEdit(role)">编辑</el-button>
          <el-button link type="danger" @click="remove(role)">删除</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑角色' : '新增角色'" width="640px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="82px">
        <el-form-item label="角色码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="Boolean(form.id)"
            placeholder="例如 AUDITOR，2-32 位大写字母、数字或下划线"
          />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="2-32 个字符" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="权限">
          <div class="perm-groups">
            <div v-for="group in permissionGroups" :key="group.name" class="perm-group">
              <div class="perm-group-name">{{ group.name }}</div>
              <el-checkbox-group v-model="form.permissions">
                <el-checkbox v-for="perm in group.items" :key="perm.code" :value="perm.code">
                  {{ perm.label }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const roles = ref([])
const permissions = ref([])
const canManage = computed(() => auth.hasPermission('role:manage'))

const permissionGroups = computed(() => {
  const groups = new Map()
  permissions.value.forEach((perm) => {
    if (!groups.has(perm.group)) groups.set(perm.group, [])
    groups.get(perm.group).push(perm)
  })
  return Array.from(groups.entries()).map(([name, items]) => ({ name, items }))
})

const permissionMap = computed(() => {
  const map = {}
  permissions.value.forEach((perm) => {
    map[perm.code] = perm.label
  })
  return map
})

function permissionLabel(code) {
  return permissionMap.value[code] || code
}

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, code: '', name: '', description: '', permissions: [] })
const rules = {
  code: [
    { required: true, message: '请输入角色码', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]{1,31}$/,
      message: '角色码需为 2-32 位大写字母、数字或下划线',
      trigger: 'blur'
    }
  ],
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const [roleData, permissionData] = await Promise.all([adminApi.roles(), adminApi.permissions()])
    roles.value = roleData
    permissions.value = permissionData
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { id: null, code: '', name: '', description: '', permissions: [] })
  formVisible.value = true
}

function openEdit(role) {
  Object.assign(form, {
    id: role.id,
    code: role.code,
    name: role.name,
    description: role.description || '',
    permissions: [...role.permissions]
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
      await adminApi.updateRole(form.id, {
        name: form.name,
        description: form.description || null,
        permissions: form.permissions
      })
      ElMessage.success('角色已更新')
    } else {
      await adminApi.createRole({
        code: form.code,
        name: form.name,
        description: form.description || null,
        permissions: form.permissions
      })
      ElMessage.success('角色已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(role) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${role.name}」？仍有用户使用时无法删除。`, '删除角色', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (error) {
    return
  }
  await adminApi.deleteRole(role.id)
  ElMessage.success('角色已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
  min-height: 220px;
}

.role-card {
  padding: 18px 20px 16px;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.role-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.role-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.role-code {
  display: inline-block;
  margin-top: 4px;
  font-size: 10px;
  color: var(--kb-muted);
  letter-spacing: 0.6px;
}

.role-users {
  font-size: 11px;
  color: var(--kb-muted);
  white-space: nowrap;
}

.role-desc {
  margin: 12px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--kb-muted);
  min-height: 20px;
}

.role-perms {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  align-content: flex-start;
}

.perm-tag {
  background: var(--kb-surface-2);
}

.empty-perm {
  font-size: 11px;
}

.role-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--kb-line-soft);
}

.perm-groups {
  width: 100%;
  display: grid;
  gap: 12px;
}

.perm-group {
  padding: 10px 12px;
  border: 1px solid var(--kb-line-soft);
  border-radius: 7px;
  background: var(--kb-surface-2);
}

.perm-group-name {
  font-size: 11px;
  font-weight: 600;
  color: var(--kb-accent);
  letter-spacing: 0.6px;
  margin-bottom: 4px;
}

.perm-group :deep(.el-checkbox) {
  margin-right: 18px;
}
</style>
