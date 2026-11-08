<template>
  <div class="page-shell">
    <div class="page-head">
      <div>
        <h1 class="page-title">部门管理</h1>
        <p class="page-subtitle">维护组织结构，部门可用于知识库成员授权。层级最多 5 级，删除前需先清空成员与引用。</p>
      </div>
      <el-button v-if="canManage" type="primary" :icon="Plus" size="large" @click="openCreate(null)">
        新增部门
      </el-button>
    </div>

    <div class="surface table-shell">
      <div class="table-tools">
        <span class="count">共 {{ rows.length }} 个部门</span>
        <el-button :icon="Refresh" circle plain :loading="loading" @click="load" />
      </div>
      <el-table
        v-loading="loading"
        :data="tree"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column label="部门" min-width="240">
          <template #default="{ row }">
            <div class="dept-cell">
              <el-icon><OfficeBuilding /></el-icon>
              <strong>{{ row.name }}</strong>
              <el-tag v-if="!row.active" size="small" type="info" effect="plain">已停用</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220">
          <template #default="{ row }">
            <span class="muted">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="80" align="center">
          <template #default="{ row }">{{ row.userCount }}</template>
        </el-table-column>
        <el-table-column label="知识库" width="90" align="center">
          <template #default="{ row }">{{ row.seriesCount }}</template>
        </el-table-column>
        <el-table-column label="排序" width="70" align="center">
          <template #default="{ row }">{{ row.sortOrder }}</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCreate(row)">新增下级</el-button>
            <el-button link @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.active ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.active ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !rows.length" description="暂无部门" :image-size="110">
        <el-button type="primary" @click="openCreate(null)">创建第一个部门</el-button>
      </el-empty>
    </div>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑部门' : '新增部门'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="82px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="2-32 个字符" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="form.parentId" placeholder="无（顶级部门）" clearable class="full">
            <el-option
              v-for="dept in parentOptions"
              :key="dept.id"
              :label="dept.label"
              :value="dept.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
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
import { OfficeBuilding, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const canManage = computed(() => auth.hasPermission('department:manage'))

const tree = computed(() => {
  const map = new Map(rows.value.map((row) => [row.id, { ...row, children: [] }]))
  const roots = []
  map.forEach((node) => {
    if (node.parentId && map.has(node.parentId)) map.get(node.parentId).children.push(node)
    else roots.push(node)
  })
  const sort = (nodes) =>
    nodes
      .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id)
      .forEach((node) => {
        node.children = node.children.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id)
        sort(node.children)
      })
  sort(roots)
  return roots
})

const formVisible = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, name: '', parentId: null, sortOrder: 0, description: '' })
const rules = {
  name: [{ required: true, message: '请输入部门名称', trigger: 'blur' }]
}

const descendants = computed(() => {
  if (!form.id) return new Set()
  const children = new Map()
  rows.value.forEach((row) => {
    if (!children.has(row.parentId)) children.set(row.parentId, [])
    children.get(row.parentId).push(row.id)
  })
  const result = new Set()
  const walk = (id) => {
    ;(children.get(id) || []).forEach((child) => {
      if (!result.has(child)) {
        result.add(child)
        walk(child)
      }
    })
  }
  walk(form.id)
  return result
})

const parentOptions = computed(() => {
  const map = new Map(rows.value.filter((row) => row.id !== form.id && !descendants.value.has(row.id)).map((row) => [row.id, { ...row, children: [] }]))
  const roots = []
  map.forEach((row) => {
    if (row.parentId && map.has(row.parentId)) map.get(row.parentId).children.push(row)
    else roots.push(row)
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
    rows.value = await adminApi.departments()
  } finally {
    loading.value = false
  }
}

function openCreate(parent) {
  Object.assign(form, {
    id: null,
    name: '',
    parentId: parent ? parent.id : null,
    sortOrder: 0,
    description: ''
  })
  formVisible.value = true
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    parentId: row.parentId,
    sortOrder: row.sortOrder,
    description: row.description || ''
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
    const payload = {
      name: form.name,
      parentId: form.parentId || null,
      sortOrder: form.sortOrder,
      description: form.description || null
    }
    if (form.id) {
      await adminApi.updateDepartment(form.id, payload)
      ElMessage.success('部门已更新')
    } else {
      await adminApi.createDepartment(payload)
      ElMessage.success('部门已创建')
    }
    formVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row) {
  const action = row.active ? '停用' : '启用'
  await adminApi.setDepartmentStatus(row.id, !row.active)
  ElMessage.success(`已${action}`)
  await load()
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除部门「${row.name}」？存在下级部门、用户或知识库引用时无法删除。`,
      '删除部门',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (error) {
    return
  }
  await adminApi.deleteDepartment(row.id)
  ElMessage.success('部门已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.table-shell {
  padding: 4px 8px 8px;
  overflow: hidden;
}

.table-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 8px 12px;
}

.count {
  font-size: 11px;
  color: var(--kb-muted);
}

.dept-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  vertical-align: middle;
}

.dept-cell .el-icon {
  color: var(--kb-primary);
}

.dept-cell strong {
  font-size: 13px;
  font-weight: 500;
}

.full {
  width: 100%;
}
</style>
