<script setup lang="ts">
// 登录页（B3 §1）：学号 + 密码，无注册入口。
// 失败文案完全交给后端（10006「学号或密码不正确」），前端不区分"账号不存在/密码错"，
// 否则等于把账号枚举能力搬到界面上。
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { resolveLoginRedirect } from '@/router'
import { BizError } from '@/api/http'
import { useAuth } from '@/composables/useAuth'
import { useRequest } from '@/composables/useRequest'
import ErrorBanner from '@/components/common/ErrorBanner.vue'
import FieldError from '@/components/common/FieldError.vue'
import type { FieldErrorItem, LoginPayload } from '@/types/api'

const route = useRoute()
const router = useRouter()
const { ensureSession, signIn } = useAuth()

const form = ref({ studentNo: '', password: '' })
const { error, loading, run } = useRequest(signIn, { immediate: false })

const fieldErrors = computed<FieldErrorItem[]>(() =>
  error.value instanceof BizError ? error.value.fieldErrors : []
)
const hasFieldError = (field: string) => fieldErrors.value.some((item) => item.field === field)
const describedBy = (field: string) => (hasFieldError(field) ? `field-${field}-error` : undefined)

/** 登录成功后回到被守卫拦下的地址；没有 redirect 时回首页 */
const redirectTarget = computed(() => resolveLoginRedirect(route.query.redirect))
const hasRedirect = computed(() => redirectTarget.value !== '/')

function submit(): void {
  if (loading.value) return
  // 空值/超长交给后端出 10001 + fieldErrors，前端不另造一套文案
  void run({ studentNo: form.value.studentNo.trim(), password: form.value.password } satisfies LoginPayload).then(
    (user) => {
      if (user) void router.replace(redirectTarget.value)
    }
  )
}

onMounted(async () => {
  // 已登录再进登录页：直接去目标页，避免"登录了还看到登录表单"
  const user = await ensureSession()
  if (user) void router.replace(redirectTarget.value)
})
</script>

<template>
  <section class="login">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">登录</h1>
        <p class="page-head__desc">用学号和密码登录后即可发布、编辑启事并查看通知。</p>
      </div>
    </header>

    <p v-if="hasRedirect" class="alert alert--info login__hint" role="status">
      <span class="alert__title">请先登录</span>
      <span class="alert__text">登录成功后会自动回到刚才访问的页面。</span>
    </p>

    <form class="panel login__form" novalidate @submit.prevent="submit">
      <div class="field">
        <label class="field__label" for="field-studentNo">学号</label>
        <input
          id="field-studentNo"
          v-model="form.studentNo"
          class="control"
          type="text"
          name="username"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          maxlength="32"
          placeholder="例：demo01"
          :aria-invalid="hasFieldError('studentNo') ? 'true' : undefined"
          :aria-describedby="describedBy('studentNo')"
        />
        <FieldError :errors="fieldErrors" field="studentNo" />
      </div>

      <div class="field">
        <label class="field__label" for="field-password">密码</label>
        <input
          id="field-password"
          v-model="form.password"
          class="control"
          type="password"
          name="password"
          autocomplete="current-password"
          maxlength="64"
          placeholder="请输入密码"
          :aria-invalid="hasFieldError('password') ? 'true' : undefined"
          :aria-describedby="describedBy('password')"
        />
        <FieldError :errors="fieldErrors" field="password" />
      </div>

      <!-- 非字段级失败（401 凭证错 / 网络失败）统一用页级提示，文案取自后端 message -->
      <ErrorBanner
        v-if="error && fieldErrors.length === 0"
        :error="error"
        title="登录失败"
      />

      <button type="submit" class="btn btn--primary btn--lg btn--block" :disabled="loading">
        <span v-if="loading" class="btn__spinner" aria-hidden="true"></span>
        {{ loading ? '登录中…' : '登录' }}
      </button>

      <p class="field__hint login__note">账号由管理员开通，不开放注册。</p>
    </form>
  </section>
</template>

<style scoped>
.login {
  max-width: 420px;
  margin: 0 auto;
}

.login__hint {
  margin-bottom: var(--sp-4);
}

.login__form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.login__note {
  margin: 0;
  text-align: center;
}
</style>
