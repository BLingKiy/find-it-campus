// 展示层派生类型（基线 frontend-baseline.md §4 `types/domain.ts`）
// 2.1 骨架阶段仅占位：4.1 落页面时把「枚举 → 中文文案」的映射函数补到这里或 *Tag 组件
import type { MatchItem, RecordDetail, RecordListItem, RecordQuery, RecordStatus, RecordType } from './api'

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

/**
 * 列表页筛选表单的本地形态：`type`/`categoryId`/`keyword` 的 `''` = 不限，
 * 写回 URL 时被 useRouteQueryState 丢弃；`status` 的「不限」必须用 `ALL`
 * （契约 §3.1 的服务端取值），否则 URL 与表单会各说各话。
 */
export interface ListFilterState {
  type: '' | RecordType
  status: RecordStatus | 'ALL'
  categoryId: string
  keyword: string
}

/** 筛选态 → 契约 RecordQuery：服务端 status 默认 PENDING，故「不限」显式传 ALL（契约 §3.1） */
export function toRecordQuery(
  filter: ListFilterState
): Pick<RecordQuery, 'type' | 'status' | 'categoryId' | 'keyword'> {
  return {
    type: filter.type || undefined,
    status: filter.status,
    categoryId: filter.categoryId ? Number(filter.categoryId) : undefined,
    keyword: filter.keyword.trim() || undefined
  }
}

export interface PageVM<T> {
  items: T[]
  total: number
  totalPages: number
  hasNext: boolean
}
