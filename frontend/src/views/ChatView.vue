<template>
  <div class="chat-page">
    <aside class="session-rail">
      <ChatSessionList
        :sessions="sessions"
        :active-id="activeId"
        :loading="sessionsLoading"
        @select="selectSession"
        @create="openCreateDialog"
        @rename="renameSession"
        @delete="deleteSession"
      />
    </aside>

    <section class="chat-main">
      <header class="chat-header">
        <div class="chat-title-wrap">
          <el-button class="mobile-session-btn" text :icon="Menu" @click="drawerVisible = true" />
          <div class="chat-title-block">
            <div class="chat-title">{{ activeSession?.title || '智能问答' }}</div>
            <div class="chat-sub">
              <template v-if="activeSession">
                <el-icon><Collection /></el-icon>
                <span class="series-name">{{ activeSession.seriesName }}</span>
                <span v-if="!activeSession.seriesAvailable" class="readonly-flag">知识库不可用</span>
                <span v-else class="private-flag">仅自己可见</span>
              </template>
              <span v-else>每个会话绑定一个知识库，会话与消息均按账号隔离</span>
            </div>
          </div>
        </div>
        <div class="chat-actions">
          <el-tag size="small" effect="plain" :type="engineTagType">{{ engineTagText }}</el-tag>
          <el-button :icon="Plus" @click="openCreateDialog">新建会话</el-button>
        </div>
      </header>

      <div ref="scrollRef" v-loading="messagesLoading" class="chat-canvas">
        <div v-if="!activeSession" class="empty-chat">
          <div class="empty-mark">
            <el-icon><ChatDotRound /></el-icon>
          </div>
          <h2>开始一段知识库对话</h2>
          <p>选择知识库创建会话。你可以同时保留多个会话，它们会按最近活跃时间排列，且只有你能看到。</p>
          <div v-if="seriesList.length" class="empty-form">
            <el-select v-model="pendingSeriesId" placeholder="选择知识库" class="empty-select">
              <el-option v-for="item in seriesList" :key="item.id" :label="item.name" :value="item.id">
                <span>{{ item.name }}</span>
                <span class="option-meta">{{ item.deptName }} · {{ item.readyDocCount }} 篇就绪</span>
              </el-option>
            </el-select>
            <el-button type="primary" :icon="Plus" :loading="creating" @click="createSession">
              创建会话
            </el-button>
          </div>
          <div v-else-if="!seriesLoading" class="empty-tip">
            暂无可访问的知识库，先创建知识库并完成文档解析。
            <el-button link type="primary" @click="router.push('/series/create')">去创建</el-button>
          </div>
        </div>

        <template v-else>
          <div class="system-notice">
            <span>
              正在与「{{ activeSession.seriesName }}」对话 · 本次会话仅你可见 · 自动带入最近 6 轮上下文用于理解追问
            </span>
          </div>

          <template v-for="item in renderedMessages" :key="item.id">
            <div v-if="item.role === 'system'" class="system-notice error-notice">
              <span>{{ item.text }}</span>
            </div>

            <template v-else>
              <div v-if="item.showDate" class="date-divider">
                <span>{{ dateLabel(item.time) }}</span>
              </div>
              <div v-if="item.showTime" class="time-divider">
                <span>{{ clock(item.time) }}</span>
              </div>

              <div class="message-row" :class="item.role">
                <div v-if="item.role === 'assistant'" class="chat-avatar assistant-avatar">
                  <span>知</span>
                </div>

                <div class="message-content">
                  <div class="bubble" :class="item.role">
                    <p class="bubble-text">{{ item.text }}</p>

                    <div v-if="item.citations?.length" class="bubble-extra">
                      <button type="button" class="citation-toggle" @click="toggleCitations(item)">
                        <el-icon><Document /></el-icon>
                        引用来源 {{ item.citations.length }}
                        <el-icon class="arrow" :class="{ open: item.citationsOpen }"><ArrowDown /></el-icon>
                      </button>
                      <div v-if="item.citationsOpen" class="citation-list">
                        <div
                          v-for="(citation, index) in item.citations"
                          :key="`${citation.chunkId}-${index}`"
                          class="citation-card"
                        >
                          <div class="citation-head">
                            <span class="citation-rank">{{ index + 1 }}</span>
                            <div class="citation-title">
                              <strong>{{ citation.documentName || '未知文档' }}</strong>
                              <span>
                                {{ citation.titlePath || `切片 #${citation.seq}` }}
                                <template v-if="citation.pageNo"> · 第 {{ citation.pageNo }} 页</template>
                              </span>
                            </div>
                            <span class="citation-score">{{ Number(citation.score || 0).toFixed(3) }}</span>
                          </div>
                          <p class="citation-content">{{ citation.content }}</p>
                          <button
                            v-if="citation.parentContent"
                            type="button"
                            class="parent-toggle"
                            @click="toggleParent(item, citation.chunkId)"
                          >
                            {{ item.expandedParents?.includes(citation.chunkId) ? '收起父切片' : '查看父切片上下文' }}
                          </button>
                          <div
                            v-if="item.expandedParents?.includes(citation.chunkId) && citation.parentContent"
                            class="parent-content"
                          >
                            {{ citation.parentContent }}
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div v-if="item.role === 'assistant' && item.meta" class="message-meta">{{ item.meta }}</div>
                </div>

                <div v-if="item.role === 'user'" class="chat-avatar user-avatar">
                  <span>{{ userInitial }}</span>
                </div>
              </div>
            </template>
          </template>

          <div v-if="typing" class="message-row assistant">
            <div class="chat-avatar assistant-avatar"><span>知</span></div>
            <div class="bubble assistant typing-bubble">
              <span class="dot" />
              <span class="dot" />
              <span class="dot" />
            </div>
          </div>

          <div v-if="showWelcomePrompts" class="welcome-prompts">
            <span class="welcome-label">可以这样问</span>
            <button
              v-for="prompt in quickPrompts"
              :key="prompt"
              type="button"
              class="prompt-chip"
              @click="sendPrompt(prompt)"
            >
              {{ prompt }}
            </button>
          </div>
        </template>

        <div ref="bottomRef" />
      </div>

      <footer v-if="activeSession" class="chat-input-bar">
        <div v-if="!activeSession.seriesAvailable" class="readonly-bar">
          所属知识库已删除或当前账号无权访问，该会话仅可查看历史消息。
        </div>
        <div v-else class="input-shell">
          <el-input
            ref="inputRef"
            v-model="draft"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            resize="none"
            maxlength="200"
            placeholder="输入问题，Enter 发送"
            @keydown.enter.exact.prevent="send"
          />
          <div class="input-foot">
            <span class="input-hint">
              Enter 发送 · Shift + Enter 换行 · 检索模式：{{ retrievalMode }} · Top{{ retrievalConfig.topK }}
              · 阈值 {{ Number(retrievalConfig.threshold).toFixed(2) }} · 带最近 6 轮上下文
            </span>
            <el-button
              type="primary"
              :icon="Promotion"
              :loading="typing"
              :disabled="!draft.trim()"
              @click="send"
            >
              发送
            </el-button>
          </div>
        </div>
      </footer>
    </section>

    <el-drawer v-model="drawerVisible" direction="ltr" size="86%" :with-header="false" class="session-drawer">
      <ChatSessionList
        :sessions="sessions"
        :active-id="activeId"
        :loading="sessionsLoading"
        @select="selectSession"
        @create="openCreateDialog"
        @rename="renameSession"
        @delete="deleteSession"
      />
    </el-drawer>

    <el-dialog v-model="createVisible" title="新建会话" width="460px">
      <el-form label-width="82px">
        <el-form-item label="知识库">
          <el-select v-model="pendingSeriesId" placeholder="选择知识库" class="full" :loading="seriesLoading">
            <el-option v-for="item in seriesList" :key="item.id" :label="item.name" :value="item.id">
              <span>{{ item.name }}</span>
              <span class="option-meta">{{ item.readyDocCount }} 篇就绪</span>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dialog-hint">会话创建后可在左侧切换；首条消息会自动生成会话标题。</p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="!pendingSeriesId" @click="createSession">
          创建会话
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown,
  ChatDotRound,
  Collection,
  Document,
  Menu,
  Plus,
  Promotion
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi, configApi, seriesApi } from '../api'
import { useAuthStore } from '../stores/auth'
import ChatSessionList from '../components/ChatSessionList.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const sessions = ref([])
const sessionsLoading = ref(false)
const seriesList = ref([])
const seriesLoading = ref(false)
const activeId = ref(null)
const messages = ref([])
const messagesLoading = ref(false)
const draft = ref('')
const typing = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const drawerVisible = ref(false)
const pendingSeriesId = ref(null)
const scrollRef = ref(null)
const bottomRef = ref(null)
const inputRef = ref(null)

const retrievalConfig = reactive({ topK: 5, threshold: 0.3, hybrid: true, rerank: true })
const retrievalMode = ref('混合检索')
const engineTagText = ref('检索增强生成')
const engineTagType = ref('info')

const userInitial = computed(() => (auth.displayName || 'U').slice(0, 1))
const activeSession = computed(() => sessions.value.find((item) => item.id === activeId.value) || null)

const quickPrompts = [
  '这个知识库包含哪些制度？',
  '帮我总结绩效工资的档位',
  '年假与调休如何计算？'
]

const showWelcomePrompts = computed(
  () => Boolean(activeSession.value?.seriesAvailable) && !typing.value && messages.value.length === 0
)

const renderedMessages = computed(() => {
  const result = []
  let lastRole = null
  let lastTime = 0
  let lastDate = ''
  messages.value.forEach((message) => {
    const time = new Date(message.time)
    const dateKey = time.toDateString()
    const showDate = dateKey !== lastDate
    const sameGroup =
      message.role !== 'system' &&
      lastRole === message.role &&
      !showDate &&
      time.getTime() - lastTime < 2 * 60 * 1000
    result.push({
      ...message,
      showDate,
      showTime: message.role !== 'system' && !sameGroup,
      grouped: sameGroup
    })
    lastDate = dateKey
    if (message.role !== 'system') {
      lastRole = message.role
      lastTime = time.getTime()
    }
  })
  return result
})

function clock(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(new Date(value))
}

function dateLabel(value) {
  const target = new Date(value)
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(today.getDate() - 1)
  if (target.toDateString() === today.toDateString()) return '今天'
  if (target.toDateString() === yesterday.toDateString()) return '昨天'
  return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric' }).format(target)
}

function metaText(meta) {
  if (!meta) return ''
  const engine = meta.engine === 'ELASTICSEARCH' ? 'ES 检索' : '本地检索'
  const llm = meta.llmUsed ? `Ollama ${meta.model || ''}`.trim() : '未生成'
  return `${engine} · ${llm} · 候选 ${meta.totalCandidates ?? 0} · ${meta.retrievalTookMs ?? 0}ms`
}

function toLocalMessage(message) {
  const assistant = message.role === 'ASSISTANT'
  return {
    id: `srv-${message.id}`,
    role: assistant ? 'assistant' : 'user',
    time: message.createdAt,
    text: message.content,
    citations: message.citations || [],
    citationsOpen:
      assistant && message.meta && !message.meta.llmUsed && (message.citations || []).length > 0,
    expandedParents: [],
    meta: assistant ? metaText(message.meta) : '',
    metaRaw: assistant ? message.meta : null
  }
}

function applyEngineTag(meta) {
  if (!meta) {
    engineTagText.value = '检索增强生成'
    engineTagType.value = 'info'
    return
  }
  const engine = meta.engine === 'ELASTICSEARCH' ? 'ES 检索' : '本地检索'
  if (meta.noAnswer) {
    engineTagText.value = `${engine} · 无匹配`
    engineTagType.value = 'info'
    return
  }
  engineTagText.value = `${engine} · ${meta.llmUsed ? 'Ollama 生成' : '生成不可用'}`
  engineTagType.value = meta.llmUsed ? 'success' : 'warning'
}

async function loadSeries() {
  seriesLoading.value = true
  try {
    const data = await seriesApi.list({ scope: 'accessible', page: 1, size: 100 })
    seriesList.value = data.items || []
  } finally {
    seriesLoading.value = false
  }
  const queryId = Number(route.query.seriesId)
  const storedId = Number(localStorage.getItem('kb_last_series'))
  pendingSeriesId.value =
    seriesList.value.find((item) => item.id === queryId)?.id ||
    seriesList.value.find((item) => item.id === storedId)?.id ||
    seriesList.value[0]?.id ||
    null
}

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const data = await chatApi.sessions()
    sessions.value = (data || []).map((session) => ({ ...session, messageCount: session.messageCount || 0 }))
  } finally {
    sessionsLoading.value = false
  }
}

async function loadMessages(session) {
  messagesLoading.value = true
  resetEngineTag()
  try {
    const data = await chatApi.messages(session.id)
    messages.value = (data || []).map(toLocalMessage)
    const lastAssistant = [...messages.value]
      .reverse()
      .find((item) => item.role === 'assistant' && item.metaRaw)
    applyEngineTag(lastAssistant?.metaRaw)
    scrollToBottom()
  } catch (error) {
    messages.value = []
    if (error?.code === 'KB_40408') {
      sessions.value = sessions.value.filter((item) => item.id !== session.id)
      activeId.value = null
      ElMessage.warning('会话已被删除')
    }
  } finally {
    messagesLoading.value = false
  }
}

async function loadRetrievalConfig(seriesId) {
  try {
    const config = await configApi.current(seriesId)
    const retrieval = config?.retrieval
    if (retrieval) {
      retrievalConfig.topK = Math.min(20, retrieval.rerankTopN || 5)
      retrievalConfig.threshold = retrieval.similarityThreshold ?? 0.3
      retrievalConfig.hybrid = retrieval.hybridEnabled !== false
      retrievalConfig.rerank = retrieval.rerankEnabled !== false
      retrievalMode.value = retrievalConfig.hybrid ? '混合检索' : '向量检索'
    }
  } catch (error) {
    retrievalConfig.topK = 5
    retrievalConfig.threshold = 0.3
    retrievalMode.value = '混合检索'
  }
}

async function selectSession(session) {
  if (!session) return
  if (typing.value) {
    ElMessage.warning('正在生成回答，请稍候')
    return
  }
  drawerVisible.value = false
  activeId.value = session.id
  localStorage.setItem('kb_last_series', String(session.seriesId))
  if (route.query.sessionId !== String(session.id)) {
    router.replace({ path: '/chat', query: { sessionId: session.id } })
  }
  await Promise.all([loadMessages(session), loadRetrievalConfig(session.seriesId)])
}

function openCreateDialog() {
  if (!seriesList.value.length) {
    ElMessage.warning('暂无可访问的知识库，请先创建知识库')
    return
  }
  createVisible.value = true
}

async function createSession() {
  if (!pendingSeriesId.value) {
    ElMessage.warning('请选择知识库')
    return
  }
  creating.value = true
  try {
    const data = await chatApi.createSession(pendingSeriesId.value)
    const session = { ...data, messageCount: 0 }
    sessions.value.unshift(session)
    createVisible.value = false
    localStorage.setItem('kb_last_series', String(session.seriesId))
    await selectSession(session)
  } finally {
    creating.value = false
  }
}

async function renameSession(session) {
  try {
    const { value } = await ElMessageBox.prompt('会话名称', '重命名会话', {
      inputValue: session.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (value) => (value && value.trim() ? true : '名称不能为空')
    })
    const data = await chatApi.renameSession(session.id, value.trim())
    Object.assign(session, { title: data.title })
    ElMessage.success('已重命名')
  } catch (error) {
    // canceled
  }
}

async function deleteSession(session) {
  try {
    await ElMessageBox.confirm(
      `确认删除会话「${session.title}」？消息将一并删除且不可恢复。`,
      '删除会话',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (error) {
    return
  }
  await chatApi.deleteSession(session.id)
  sessions.value = sessions.value.filter((item) => item.id !== session.id)
  if (activeId.value === session.id) {
    activeId.value = null
    messages.value = []
    if (sessions.value.length) await selectSession(sessions.value[0])
    else router.replace({ path: '/chat' })
  }
  ElMessage.success('会话已删除')
}

function toggleCitations(message) {
  message.citationsOpen = !message.citationsOpen
  if (message.citationsOpen) scrollToBottom()
}

function toggleParent(message, chunkId) {
  if (!message.expandedParents) message.expandedParents = []
  const index = message.expandedParents.indexOf(chunkId)
  if (index >= 0) message.expandedParents.splice(index, 1)
  else message.expandedParents.push(chunkId)
}

function sendPrompt(prompt) {
  draft.value = prompt
  send()
}

async function send() {
  const query = draft.value.trim()
  const session = activeSession.value
  if (!query || typing.value || !session) return
  if (!session.seriesAvailable) {
    ElMessage.warning('所属知识库不可用，无法继续提问')
    return
  }
  draft.value = ''
  typing.value = true
  resetEngineTag()
  const tempId = `tmp-${Date.now()}`
  messages.value.push({
    id: tempId,
    role: 'user',
    time: new Date().toISOString(),
    text: query,
    citations: [],
    citationsOpen: false,
    expandedParents: [],
    meta: ''
  })
  scrollToBottom()

  try {
    const response = await chatApi.send(session.id, query)
    typing.value = false
    const tempIndex = messages.value.findIndex((item) => item.id === tempId)
    const userMessage = toLocalMessage(response.userMessage)
    if (tempIndex >= 0) messages.value.splice(tempIndex, 1, userMessage)
    else messages.value.push(userMessage)
    messages.value.push(toLocalMessage(response.assistantMessage))
    applyEngineTag(response.assistantMessage.meta)

    session.title = response.sessionTitle
    session.messageCount = (session.messageCount || 0) + 2
    session.updatedAt = new Date().toISOString()
    sessions.value = [session, ...sessions.value.filter((item) => item.id !== session.id)]
    scrollToBottom()
  } catch (error) {
    typing.value = false
    messages.value = messages.value.filter((item) => item.id !== tempId)
    messages.value.push({
      id: `err-${Date.now()}`,
      role: 'system',
      time: new Date().toISOString(),
      text: error?.message || '发送失败，请稍后重试'
    })
    scrollToBottom()
  }
}

function resetEngineTag() {
  engineTagText.value = '检索增强生成'
  engineTagType.value = 'info'
}

function scrollToBottom() {
  requestAnimationFrame(() => {
    bottomRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  })
}

watch(
  () => messages.value.length,
  () => scrollToBottom()
)

onMounted(async () => {
  await Promise.all([loadSeries(), loadSessions()])
  if (!sessions.value.length) return
  const queryId = Number(route.query.sessionId)
  const target = sessions.value.find((item) => item.id === queryId) || sessions.value[0]
  await selectSession(target)
})
</script>

<style scoped>
.chat-page {
  height: calc(100vh - 64px);
  min-height: 540px;
  display: flex;
  background: var(--kb-bg);
}

.session-rail {
  width: 272px;
  flex-shrink: 0;
  border-right: 1px solid var(--kb-line);
  min-height: 0;
}

.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #f1efe8;
}

.chat-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 22px;
  background: var(--kb-surface);
  border-bottom: 1px solid var(--kb-line);
}

.chat-title-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.mobile-session-btn {
  display: none;
}

.chat-title-block {
  flex: 1;
  min-width: 0;
}

.chat-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--kb-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 520px;
}

.chat-sub {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 3px;
  min-width: 0;
  font-size: 10px;
  color: var(--kb-muted);
}

.chat-sub .series-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-sub .el-icon {
  font-size: 11px;
  color: var(--kb-primary);
}

.private-flag {
  flex-shrink: 0;
  white-space: nowrap;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-size: 9px;
}

.readonly-flag {
  flex-shrink: 0;
  white-space: nowrap;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--kb-danger-soft);
  color: var(--kb-danger);
  font-size: 9px;
}

.chat-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.chat-canvas {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 26px 26px;
  background: #f1efe8;
}

.empty-chat {
  max-width: 540px;
  margin: 9vh auto 0;
  text-align: center;
  padding: 0 24px;
}

.empty-mark {
  width: 58px;
  height: 58px;
  margin: 0 auto 18px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: var(--kb-primary);
  color: #f2f0e6;
  font-size: 26px;
  box-shadow: inset 0 0 0 1px rgba(169, 119, 63, 0.35);
}

.empty-chat h2 {
  margin: 0 0 10px;
  font-family: var(--kb-serif);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.4px;
}

.empty-chat p {
  margin: 0 auto 22px;
  max-width: 430px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--kb-muted);
}

.empty-form {
  display: flex;
  justify-content: center;
  gap: 10px;
}

.empty-select {
  width: 240px;
}

.empty-tip {
  font-size: 12px;
  color: var(--kb-muted);
}

.date-divider,
.time-divider {
  display: flex;
  justify-content: center;
  margin: 10px 0;
}

.date-divider span {
  font-size: 10px;
  color: var(--kb-muted);
  background: rgba(253, 252, 248, 0.65);
  border-radius: 3px;
  padding: 3px 10px;
}

.time-divider span {
  font-size: 10px;
  color: var(--kb-faint);
}

.system-notice {
  display: flex;
  justify-content: center;
  margin: 10px 0;
}

.system-notice span {
  max-width: 80%;
  text-align: center;
  font-size: 11px;
  line-height: 1.7;
  color: var(--kb-muted);
  background: rgba(253, 252, 248, 0.62);
  border-radius: 4px;
  padding: 5px 12px;
}

.error-notice span {
  color: #98504a;
  background: rgba(251, 238, 236, 0.9);
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 6px 0;
  animation: message-in 0.22s ease;
}

.message-row.user {
  justify-content: flex-end;
}

@keyframes message-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.chat-avatar {
  width: 36px;
  height: 36px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  user-select: none;
}

.assistant-avatar {
  background: var(--kb-primary);
  color: #f2f0e6;
}

.user-avatar {
  background: #95ec69;
  color: #1f3b12;
}

.message-content {
  max-width: min(73%, 720px);
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-row.user .message-content {
  align-items: flex-end;
}

.bubble {
  position: relative;
  padding: 10px 13px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  box-shadow: 0 1px 1px rgba(31, 36, 31, 0.03);
}

.bubble.assistant {
  background: #fff;
  color: #232822;
  border-radius: 4px 10px 10px 10px;
}

.bubble.user {
  background: #95ec69;
  color: #15260d;
  border-radius: 10px 4px 10px 10px;
}

.bubble.assistant::before {
  content: '';
  position: absolute;
  left: -6px;
  top: 12px;
  border: 6px solid transparent;
  border-right-color: #fff;
  border-left: 0;
}

.bubble.user::after {
  content: '';
  position: absolute;
  right: -6px;
  top: 12px;
  border: 6px solid transparent;
  border-left-color: #95ec69;
  border-right: 0;
}

.bubble-text {
  margin: 0;
  white-space: pre-wrap;
}

.bubble-extra {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--kb-line-soft);
}

.citation-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: var(--kb-surface-2);
  color: var(--kb-primary);
  font-size: 11px;
  font-family: inherit;
  padding: 5px 9px;
  border-radius: 4px;
  cursor: pointer;
}

.citation-toggle:hover {
  background: var(--kb-primary-soft);
}

.citation-toggle .arrow {
  transition: transform 0.18s ease;
}

.citation-toggle .arrow.open {
  transform: rotate(180deg);
}

.citation-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.citation-card {
  border-radius: 6px;
  border: 1px solid var(--kb-line);
  background: var(--kb-surface);
  padding: 10px;
}

.citation-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.citation-rank {
  width: 20px;
  height: 20px;
  border-radius: 4px;
  display: grid;
  place-items: center;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}

.citation-title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.citation-title strong {
  font-size: 11px;
  font-weight: 600;
}

.citation-title span {
  font-size: 10px;
  color: var(--kb-muted);
}

.citation-score {
  font-size: 10px;
  color: var(--kb-primary);
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.citation-content {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.75;
  color: var(--kb-ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow: auto;
}

.parent-toggle {
  margin-top: 6px;
  border: none;
  background: transparent;
  color: var(--kb-primary);
  font-size: 10px;
  font-family: inherit;
  cursor: pointer;
  padding: 0;
}

.parent-content {
  margin-top: 6px;
  padding: 8px;
  border-radius: 4px;
  background: var(--kb-surface-2);
  font-size: 10px;
  line-height: 1.75;
  color: var(--kb-ink-soft);
  white-space: pre-wrap;
  max-height: 180px;
  overflow: auto;
}

.message-meta {
  font-size: 9px;
  color: var(--kb-faint);
  padding: 0 4px;
}

.typing-bubble {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 14px 16px;
}

.typing-bubble .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b6b1a3;
  animation: typing 1.2s infinite ease-in-out;
}

.typing-bubble .dot:nth-child(2) {
  animation-delay: 0.16s;
}

.typing-bubble .dot:nth-child(3) {
  animation-delay: 0.32s;
}

@keyframes typing {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.welcome-prompts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 16px 46px 4px;
}

.welcome-label {
  font-size: 10px;
  color: var(--kb-faint);
}

.prompt-chip {
  border: 1px solid var(--kb-line);
  background: rgba(253, 252, 248, 0.8);
  color: var(--kb-ink-soft);
  font-size: 11px;
  font-family: inherit;
  padding: 6px 11px;
  border-radius: 4px;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.prompt-chip:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
  background: var(--kb-primary-tint);
}

.chat-input-bar {
  flex-shrink: 0;
  padding: 12px 22px 16px;
  background: var(--kb-surface);
  border-top: 1px solid var(--kb-line);
}

.readonly-bar {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--kb-danger-soft);
  color: #8c4a43;
  font-size: 12px;
  text-align: center;
}

.input-shell {
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--kb-line);
  padding: 8px 10px 8px;
}

.input-shell:focus-within {
  border-color: var(--kb-primary);
}

.input-shell :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 4px 4px 6px;
  font-size: 14px;
  line-height: 1.65;
  background: transparent;
  resize: none;
}

.input-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-left: 4px;
}

.input-hint {
  font-size: 9px;
  color: var(--kb-faint);
}

.option-meta {
  float: right;
  margin-left: 14px;
  font-size: 11px;
  color: var(--kb-muted);
}

.full {
  width: 100%;
}

.dialog-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.8;
  color: var(--kb-muted);
}

@media (max-width: 1000px) {
  .session-rail {
    width: 236px;
  }
}

@media (max-width: 820px) {
  .session-rail {
    display: none;
  }

  .mobile-session-btn {
    display: inline-flex;
  }

  .chat-header {
    padding: 10px 14px;
  }

  .chat-title {
    max-width: none;
  }

  .chat-actions {
    display: none;
  }

  .input-foot {
    justify-content: flex-end;
  }

  .chat-canvas {
    padding: 14px 12px 18px;
  }

  .message-content {
    max-width: 82%;
  }

  .chat-input-bar {
    padding: 10px 12px 12px;
  }

  .input-hint {
    display: none;
  }

  .welcome-prompts {
    padding: 12px 4px 0;
  }

  .empty-form {
    flex-direction: column;
    align-items: stretch;
  }

  .empty-select {
    width: 100%;
  }
}

.session-drawer :deep(.el-drawer__body) {
  padding: 0;
}
</style>
