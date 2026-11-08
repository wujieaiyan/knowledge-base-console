<template>
  <div class="login-page">
    <section class="brand-panel">
      <div class="brand-top">
        <div class="logo-mark">
          <el-icon><Collection /></el-icon>
        </div>
        <div>
          <div class="brand-name">知序</div>
          <div class="brand-sub">Knowledge Console</div>
        </div>
      </div>

      <div class="brand-copy">
        <p class="eyebrow">KNOWLEDGE BASE ASSISTANT</p>
        <h1>让组织知识<br />可检索、可维护、可追溯</h1>
        <p class="desc">
          从文档解析、智能切片到混合检索与引用溯源，在一处完成知识库全生命周期管理。
        </p>
      </div>

      <div class="feature-row">
        <div class="feature">
          <span class="feature-index">01</span>
          <div>
            <strong>多格式解析</strong>
            <p>PDF / Word / Excel / Markdown</p>
          </div>
        </div>
        <div class="feature">
          <span class="feature-index">02</span>
          <div>
            <strong>五种切片策略</strong>
            <p>默认、按行、父子、标题增强、自定义</p>
          </div>
        </div>
        <div class="feature">
          <span class="feature-index">03</span>
          <div>
            <strong>混合检索</strong>
            <p>向量 + BM25，精排与引用溯源</p>
          </div>
        </div>
      </div>

      <div class="brand-foot">Demo workspace · 2026</div>
    </section>

    <section class="form-panel">
      <div class="form-card">
        <div class="form-head">
          <h2>登录工作台</h2>
          <p>使用企业账号进入知识库智能助手</p>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="账号" prop="account">
            <el-input v-model="form.account" size="large" placeholder="请输入账号" :prefix-icon="User" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              show-password
              placeholder="请输入密码"
              :prefix-icon="Lock"
              @keyup.enter="submit"
            />
          </el-form-item>
          <el-button class="submit-btn" type="primary" size="large" :loading="loading" @click="submit">
            进入工作台
          </el-button>
        </el-form>

        <div class="demo-block">
          <div class="demo-title">
            <span>演示账号</span>
            <span class="demo-hint">密码均为 123456</span>
          </div>
          <div class="demo-list">
            <button
              v-for="item in demoAccounts"
              :key="item.account"
              class="demo-item"
              type="button"
              @click="fillDemo(item.account)"
            >
              <span class="demo-avatar">{{ item.name.slice(0, 1) }}</span>
              <span class="demo-text">
                <strong>{{ item.name }}</strong>
                <small>{{ item.account }} · {{ item.desc }}</small>
              </span>
            </button>
          </div>
        </div>

        <p class="login-foot">登录即代表同意演示环境使用规范</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ account: 'zhangsan', password: '123456' })
const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const demoAccounts = [
  { account: 'zhangsan', name: '张三', desc: '人力资源部' },
  { account: 'lisi', name: '李四', desc: '人力资源部' },
  { account: 'wangwu', name: '王五', desc: '财务部' },
  { account: 'admin', name: '平台管理员', desc: '全部知识库' }
]

function fillDemo(account) {
  form.account = account
  form.password = '123456'
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const user = await auth.login(form.account.trim(), form.password)
    ElMessage.success(`欢迎回来，${user.name}`)
    router.replace(route.query.redirect || '/series?scope=accessible')
  } catch (error) {
    ElMessage.error(error.message || '账号或密码错误')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(460px, 1.08fr) minmax(420px, 0.92fr);
  background: var(--kb-bg);
}

.brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 46px 56px;
  color: #f0eee5;
  background: #1a211c;
}

.brand-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px);
  background-size: 100% 52px;
  pointer-events: none;
}

.brand-panel::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 3px;
  height: 100%;
  background: linear-gradient(180deg, var(--kb-accent), rgba(169, 119, 63, 0.12) 62%, transparent);
  pointer-events: none;
}

.brand-top,
.brand-copy,
.feature-row,
.brand-foot {
  position: relative;
  z-index: 1;
}

.brand-top {
  display: flex;
  align-items: center;
  gap: 14px;
}

.logo-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: var(--kb-primary);
  color: #f4f1e7;
  font-size: 20px;
  box-shadow: inset 0 0 0 1px rgba(169, 119, 63, 0.5);
}

.brand-name {
  font-family: var(--kb-serif);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 6px;
}

.brand-sub {
  font-size: 10px;
  letter-spacing: 2.2px;
  text-transform: uppercase;
  color: rgba(240, 238, 229, 0.45);
  margin-top: 3px;
}

.brand-copy {
  max-width: 620px;
  margin: 32px 0;
}

.eyebrow {
  margin: 0 0 20px;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 3.2px;
  color: #c8a877;
}

.brand-copy h1 {
  margin: 0;
  font-family: var(--kb-serif);
  font-size: clamp(34px, 3.4vw, 49px);
  line-height: 1.3;
  letter-spacing: -0.5px;
  font-weight: 500;
  color: #f5f3ea;
}

.brand-copy .desc {
  margin: 24px 0 0;
  max-width: 540px;
  color: rgba(240, 238, 229, 0.58);
  line-height: 1.95;
  font-size: 13.5px;
}

.feature-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px;
  padding-top: 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.09);
}

.feature {
  display: flex;
  gap: 10px;
}

.feature-index {
  font-size: 10px;
  color: #c8a877;
  letter-spacing: 1px;
  padding-top: 2px;
  font-family: 'JetBrains Mono', Consolas, monospace;
}

.feature strong {
  display: block;
  font-size: 12.5px;
  font-weight: 500;
  color: #ece9df;
}

.feature p {
  margin: 5px 0 0;
  font-size: 10.5px;
  line-height: 1.65;
  color: rgba(240, 238, 229, 0.4);
}

.brand-foot {
  font-size: 10px;
  letter-spacing: 2.4px;
  color: rgba(240, 238, 229, 0.3);
}

.form-panel {
  display: grid;
  place-items: center;
  padding: 40px 32px;
}

.form-card {
  width: min(408px, 100%);
  background: var(--kb-surface);
  border: 1px solid var(--kb-line);
  border-radius: 12px;
  padding: 38px 36px 28px;
  box-shadow: 0 18px 50px rgba(31, 36, 31, 0.06);
}

.form-head h2 {
  margin: 0 0 8px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.4px;
}

.form-head p {
  margin: 0 0 26px;
  color: var(--kb-muted);
  font-size: 13px;
}

.submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 14px;
  border-radius: 6px;
  background: var(--kb-primary);
  border: 1px solid var(--kb-primary);
  letter-spacing: 0.5px;
}

.submit-btn:hover {
  background: var(--kb-primary-hover);
  border-color: var(--kb-primary-hover);
}

.demo-block {
  margin-top: 26px;
  padding-top: 20px;
  border-top: 1px solid var(--kb-line-soft);
}

.demo-title {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  letter-spacing: 0.4px;
  color: var(--kb-ink-soft);
  margin-bottom: 12px;
}

.demo-hint {
  color: var(--kb-faint);
}

.demo-list {
  display: grid;
  gap: 7px;
}

.demo-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 7px;
  border: 1px solid transparent;
  background: var(--kb-surface-2);
  cursor: pointer;
  text-align: left;
  transition: background 0.16s ease, border-color 0.16s ease;
  font-family: inherit;
}

.demo-item:hover {
  background: var(--kb-primary-tint);
  border-color: #d8e4dd;
}

.demo-avatar {
  width: 30px;
  height: 30px;
  border-radius: 6px;
  background: var(--kb-primary-soft);
  color: var(--kb-primary);
  font-weight: 600;
  display: grid;
  place-items: center;
  font-size: 12px;
}

.demo-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.demo-text strong {
  font-size: 12px;
  font-weight: 500;
  color: var(--kb-ink);
}

.demo-text small {
  font-size: 10.5px;
  color: var(--kb-muted);
}

.login-foot {
  margin: 22px 0 0;
  text-align: center;
  font-size: 10.5px;
  color: var(--kb-faint);
}

@media (max-width: 980px) {
  .login-page {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    min-height: 300px;
    padding: 30px 32px;
  }

  .brand-copy {
    margin: 22px 0;
  }

  .brand-copy h1 {
    font-size: 30px;
  }

  .feature-row {
    display: none;
  }

  .brand-foot {
    display: none;
  }
}
</style>
