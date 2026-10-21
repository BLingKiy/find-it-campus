<script setup lang="ts">
// 布局壳：顶部栏 + <RouterView/> + 页脚（基线 §4 main.ts/App.vue 职责）
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="container app-header__inner">
        <RouterLink :to="{ name: 'RecordList' }" class="app-header__brand">
          <span class="app-header__logo">FI</span>
          校园失物招领
        </RouterLink>
        <nav class="app-header__nav">
          <RouterLink :to="{ name: 'RecordList' }">启事列表</RouterLink>
          <RouterLink :to="{ name: 'RecordCreate' }">发布启事</RouterLink>
        </nav>
      </div>
    </header>

    <main class="app-main">
      <div class="container">
        <RouterView />
      </div>
    </main>

    <footer class="app-footer">
      <div class="container">find-it-campus · 前端骨架 2.1-A（演示用 dev server）</div>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.app-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background-color: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

.app-header__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
}

.app-header__brand {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  color: var(--color-text);
  font-size: var(--fs-lg);
  font-weight: var(--fw-bold);
}

.app-header__brand:hover {
  text-decoration: none;
}

.app-header__logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  background-color: var(--color-primary);
  color: var(--color-bg);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
}

.app-header__nav {
  display: flex;
  gap: var(--sp-5);
  font-size: var(--fs-md);
}

.app-main {
  flex: 1 1 auto;
  padding: var(--sp-6) 0 var(--sp-8);
}

.app-footer {
  padding: var(--sp-4) 0;
  border-top: 1px solid var(--color-border);
  background-color: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}
</style>
