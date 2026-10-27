<script setup lang="ts">
// 布局壳：跳过导航 → 顶部栏 → <RouterView/> → 页脚（基线 §4 main.ts/App.vue 职责）
// 4.2：只改视觉与结构（吸顶、当前页高亮、安全区、100dvh），不动路由与数据流
</script>

<template>
  <div class="app-shell">
    <a class="skip-link" href="#main">跳到主要内容</a>

    <header class="app-header">
      <div class="container app-header__inner">
        <RouterLink :to="{ name: 'RecordList' }" class="app-header__brand">
          <span class="app-header__logo" aria-hidden="true">FI</span>
          <span class="app-header__brand-text">校园失物招领</span>
        </RouterLink>

        <nav class="app-header__nav" aria-label="主导航">
          <RouterLink :to="{ name: 'RecordList' }" class="app-header__link">启事列表</RouterLink>
          <RouterLink :to="{ name: 'RecordCreate' }" class="app-header__link app-header__link--cta">
            发布启事
          </RouterLink>
        </nav>
      </div>
    </header>

    <main id="main" class="app-main" tabindex="-1">
      <div class="container">
        <RouterView />
      </div>
    </main>

    <footer class="app-footer">
      <div class="container app-footer__inner">
        <span>find-it-campus · 校园失物招领</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  min-height: 100dvh;
}

/* ---- 顶部栏 -------------------------------------------------------------- */
.app-header {
  position: sticky;
  top: 0;
  z-index: var(--z-header);
  border-bottom: 1px solid var(--color-border);
  background-color: var(--color-surface);
}

.app-header__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  min-height: var(--header-height);
}

.app-header__brand {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  color: var(--color-text);
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  letter-spacing: -0.01em;
}

.app-header__brand:hover {
  text-decoration: none;
}

.app-header__logo {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-xs);
  background-color: var(--color-primary);
  color: var(--color-on-primary);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  letter-spacing: 0.02em;
}

.app-header__brand-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-header__nav {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--sp-1);
  font-size: var(--fs-sm);
}

/* 导航项本身就是触控目标：高度撑到 36px，窄屏也能点中 */
.app-header__link {
  display: inline-flex;
  align-items: center;
  min-height: 36px;
  padding: 0 var(--sp-2);
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  font-weight: var(--fw-medium);
  white-space: nowrap;
  transition:
    color var(--dur-fast) var(--ease-standard),
    background-color var(--dur-fast) var(--ease-standard);
}

.app-header__link:hover {
  background-color: var(--color-surface-muted);
  color: var(--color-text);
  text-decoration: none;
}

/* vue-router 自动给当前路由加 .router-link-active，并带上 aria-current="page" */
.app-header__link.router-link-active {
  color: var(--color-primary-strong);
  background-color: var(--color-primary-soft);
}

.app-header__link--cta {
  margin-left: var(--sp-1);
  border: 1px solid var(--color-border-strong);
  color: var(--color-text);
}

.app-header__link--cta.router-link-active {
  border-color: var(--color-primary);
  background-color: var(--color-primary-soft);
  color: var(--color-primary-strong);
}

/* ---- 主体与页脚 ---------------------------------------------------------- */
.app-main {
  flex: 1 1 auto;
  /* 至少占满一屏（减去吸顶 header）：这样页脚永远在首屏之外，
     列表从骨架换成真实卡片时页脚不会在视口里跳动（实测 CLS 0.069 → 0，见 ui-baseline §5.2） */
  min-height: calc(100vh - var(--header-height));
  min-height: calc(100dvh - var(--header-height));
  padding: var(--sp-5) 0 var(--sp-8);
}

/* tabindex="-1" 的 main 在程序化聚焦时不该有可见焦点环（它是跳转落点，不是控件） */
.app-main:focus {
  outline: none;
}

.app-footer {
  border-top: 1px solid var(--color-border);
  background-color: var(--color-surface);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}

.app-footer__inner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-1) var(--sp-3);
  padding-top: var(--sp-3);
  /* 底部安全区：iPhone 横屏/全面屏手势条不压住文字 */
  padding-bottom: calc(var(--sp-3) + env(safe-area-inset-bottom, 0px));
}

@media (min-width: 768px) {
  .app-main {
    padding: var(--sp-6) 0 var(--sp-8);
  }

  .app-header__brand {
    font-size: var(--fs-lg);
  }

  .app-header__nav {
    gap: var(--sp-2);
    font-size: var(--fs-md);
  }

  .app-header__link {
    padding: 0 var(--sp-3);
  }
}

/* 360px 以下：只留 logo，避免品牌文字被挤成省略号（横向溢出最常见的来源） */
@media (max-width: 359px) {
  .app-header__brand-text {
    display: none;
  }
}
</style>
