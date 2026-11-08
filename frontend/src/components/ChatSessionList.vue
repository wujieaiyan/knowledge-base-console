<template>
  <div class="session-panel">
    <div class="panel-head">
      <div>
        <strong>会话</strong>
        <span class="panel-count">{{ sessions.length }}</span>
      </div>
      <el-button text size="small" :icon="Plus" @click="$emit('create')">新建</el-button>
    </div>

    <el-input
      v-model="keyword"
      class="panel-search"
      size="small"
      placeholder="搜索会话或知识库"
      clearable
      :prefix-icon="Search"
    />

    <div v-loading="loading" class="session-list">
      <button
        v-for="session in filtered"
        :key="session.id"
        type="button"
        class="session-item"
        :class="{ active: session.id === activeId, readonly: !session.seriesAvailable }"
        @click="$emit('select', session)"
      >
        <div class="session-top">
          <span class="session-title">{{ session.title }}</span>
          <el-dropdown trigger="click" @command="(cmd) => onCommand(cmd, session)" @click.stop>
            <span class="session-menu" @click.stop>
              <el-icon><MoreFilled /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除会话</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="session-series">
          <el-icon><Collection /></el-icon>
          <span>{{ session.seriesName }}</span>
          <span v-if="!session.seriesAvailable" class="session-flag">只读</span>
        </div>
        <div class="session-foot">
          <span>{{ timeLabel(session.updatedAt) }}</span>
          <span>{{ session.messageCount }} 条消息</span>
        </div>
      </button>

      <div v-if="!loading && !filtered.length" class="session-empty">
        <span>{{ keyword ? '没有匹配的会话' : '还没有会话，点击「新建」开始' }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Collection, MoreFilled, Plus, Search } from '@element-plus/icons-vue'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'create', 'rename', 'delete'])
const keyword = ref('')

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return props.sessions
  return props.sessions.filter(
    (session) =>
      (session.title || '').toLowerCase().includes(value) ||
      (session.seriesName || '').toLowerCase().includes(value)
  )
})

function onCommand(command, session) {
  emit(command, session)
}

function timeLabel(value) {
  if (!value) return ''
  const date = new Date(value)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  if (diff >= 0 && diff < 60 * 1000) return '刚刚'
  if (diff >= 0 && diff < 60 * 60 * 1000) return `${Math.max(1, Math.floor(diff / 60000))} 分钟前`
  const clock = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
  if (date.toDateString() === now.toDateString()) return `今天 ${clock}`
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return `昨天 ${clock}`
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
}
</script>

<style scoped>
.session-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px 12px 12px;
  background: var(--kb-surface);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 10px;
}

.panel-head strong {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.panel-count {
  margin-left: 7px;
  font-size: 10px;
  color: var(--kb-faint);
}

.panel-search {
  margin-bottom: 10px;
}

.panel-search :deep(.el-input__wrapper) {
  background: var(--kb-surface-2);
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 2px;
}

.session-item {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  padding: 10px 11px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease;
}

.session-item:hover {
  background: var(--kb-surface-2);
}

.session-item.active {
  background: var(--kb-primary-tint);
  border-color: #cfe1d9;
  box-shadow: inset 2px 0 0 var(--kb-primary);
}

.session-item.readonly {
  opacity: 0.72;
}

.session-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.session-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--kb-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-menu {
  display: inline-flex;
  padding: 2px;
  border-radius: 4px;
  color: var(--kb-faint);
  opacity: 0;
  transition: opacity 0.16s ease, background 0.16s ease;
}

.session-item:hover .session-menu,
.session-item.active .session-menu {
  opacity: 1;
}

.session-menu:hover {
  background: var(--kb-surface-3);
  color: var(--kb-ink-soft);
}

.session-series {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 5px;
  font-size: 10px;
  color: var(--kb-primary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.session-series .el-icon {
  flex-shrink: 0;
  font-size: 11px;
}

.session-series span {
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-flag {
  flex-shrink: 0;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--kb-surface-3);
  color: var(--kb-muted);
  font-size: 9px;
}

.session-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  font-size: 9.5px;
  color: var(--kb-faint);
}

.session-empty {
  padding: 32px 8px;
  text-align: center;
  font-size: 11px;
  color: var(--kb-faint);
  line-height: 1.8;
}
</style>
