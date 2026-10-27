<script setup lang="ts">
// 我的启事 · 页面骨架（C2）。数据接口（GET /api/my/records）由 D2 接入：
// 本页只保证路由、登录守卫、页头与三态口径的文案位，不伪造任何列表数据。
// 从详情页关闭 / 完成 / 404 回跳时会带 query，这里给出对应的一次性提示，避免"跳过来什么都没有"。
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

interface ShellNotice {
  kind: 'success' | 'info'
  title: string
  text: string
}

const notice = computed<ShellNotice | null>(() => {
  const { updated, unavailable } = route.query
  if (updated === 'completed') {
    return {
      kind: 'success',
      title: '已标记完成',
      text: '这条启事已从公共列表隐藏；保留期内它仍会参与匹配，其他寻物启事照常能匹配到它。'
    }
  }
  if (updated === 'closed') {
    return {
      kind: 'success',
      title: '已关闭',
      text: '这条启事不再公开展示，也不再参与匹配。'
    }
  }
  if (unavailable === '1') {
    return {
      kind: 'info',
      title: '该启事已关闭或不可查看',
      text: '已完成 / 已关闭的启事不再提供公开详情；如需查看摘要，请回到「我的启事」列表。'
    }
  }
  return null
})
</script>

<template>
  <section class="my-records">
    <header class="page-head">
      <div>
        <h1 class="page-head__title">我的启事</h1>
        <p class="page-head__desc">
          管理你发布过的启事：编辑内容、标记完成或关闭。三态按「进行中 / 已完成 / 已关闭」排列。
        </p>
      </div>
      <div class="page-head__actions">
        <RouterLink :to="{ name: 'RecordCreate' }" class="btn btn--primary">
          + 发布启事
        </RouterLink>
      </div>
    </header>

    <p
      v-if="notice"
      class="alert my-records__notice"
      :class="`alert--${notice.kind}`"
      role="status"
      data-testid="my-records-notice"
    >
      <span class="alert__title">{{ notice.title }}</span>
      <span class="alert__text">{{ notice.text }}</span>
    </p>

    <div class="panel my-records__shell" data-testid="my-records-shell">
      <h2 class="panel__title">启事列表</h2>
      <p class="panel__desc">
        你的启事列表正在接入中：接入后将在这里按状态分组展示，并提供逐条的编辑 / 标记完成 / 关闭操作。
      </p>
      <p class="hint my-records__hint">
        当前可直接使用：从启事详情页进入「编辑」或「关闭」；发布入口在右上角。
      </p>
      <div class="my-records__actions">
        <RouterLink :to="{ name: 'RecordList' }" class="btn btn--ghost">
          浏览公共列表
        </RouterLink>
      </div>
    </div>
  </section>
</template>

<style scoped>
.my-records__notice {
  margin-bottom: var(--sp-4);
}

.my-records__shell {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  align-items: flex-start;
}

.my-records__hint {
  margin-top: var(--sp-1);
}

.my-records__actions {
  margin-top: var(--sp-2);
}
</style>
