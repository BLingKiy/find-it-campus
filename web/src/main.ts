import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// 顺序有意义：tokens（变量）→ base（reset/排版/无障碍地基）→ components（组件原始类）→ utilities
import './styles/tokens.css'
import './styles/base.css'
import './styles/components.css'
import './styles/utilities.css'

createApp(App).use(router).mount('#app')
