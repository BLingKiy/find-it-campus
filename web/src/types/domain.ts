// 展示层派生类型（基线 frontend-baseline.md §4 `types/domain.ts`）
// 2.1 骨架阶段仅占位：4.1 落页面时把「枚举 → 中文文案」的映射函数补到这里或 *Tag 组件
import type { MatchItem, RecordDetail, RecordListItem } from './api'

/** 列表卡片渲染所需：*Label 由 RecordTypeTag / RecordStatusTag 一处集中给出 */
export interface RecordListItemVM extends RecordListItem {
  typeLabel: string
  statusLabel: string
  occurredAtText: string
}

export interface RecordDetailVM extends RecordDetail {
  typeLabel: string
  statusLabel: string
  occurredAtText: string
  createdAtText: string
}

/** scorePercent 由后端给出，展示文案统一为「相似度 N%」，前端不二次加工（契约 §8.4） */
export interface MatchItemVM extends MatchItem {
  typeLabel: string
  statusLabel: string
  similarityText: string
}

export interface PageVM<T> {
  items: T[]
  total: number
  totalPages: number
  hasNext: boolean
}
