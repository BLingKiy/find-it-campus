// 临时样例数据（2.1 验收用渲染证据），仅在 api/records.ts 的 listRecords 内部使用
// 页面组件不得 import 本文件；4.1 后端接通后连同 listRecords 里的 TODO 一起删除
import type { PageResult, RecordListItem, RecordQuery } from '@/types/api'

const mockRecords: RecordListItem[] = [
  {
    id: 26,
    type: 'FOUND',
    status: 'PENDING',
    title: '捡到一张 unknown 的卡片',
    categoryId: null,
    categoryName: null,
    location: '大学生活动中心公告栏',
    occurredAt: '2026-10-21 13:30:00',
    createdAt: '2026-10-21 13:50:00'
  },
  {
    id: 15,
    type: 'FOUND',
    status: 'PENDING',
    title: '教室捡到卡套内有学生证',
    categoryId: 4,
    categoryName: '证件资料',
    location: '第三教学楼201',
    occurredAt: '2026-10-21 12:10:00',
    createdAt: '2026-10-21 12:30:00'
  },
  {
    id: 13,
    type: 'FOUND',
    status: 'PENDING',
    title: '图书馆拾到黑色钱包',
    categoryId: 1,
    categoryName: '钱包卡证',
    location: '图书馆三楼自习区',
    occurredAt: '2026-10-20 15:00:00',
    createdAt: '2026-10-20 15:30:00'
  },
  {
    id: 1,
    type: 'LOST',
    status: 'PENDING',
    title: '图书馆丢了黑色钱包',
    categoryId: 1,
    categoryName: '钱包卡证',
    location: '图书馆三楼自习区',
    occurredAt: '2026-10-19 17:30:00',
    createdAt: '2026-10-20 09:10:00'
  },
  {
    id: 9,
    type: 'LOST',
    status: 'CLAIMED',
    title: '丢了一个灰色双肩背包',
    categoryId: 5,
    categoryName: '箱包服饰',
    location: '第一教学楼305',
    occurredAt: '2026-10-19 15:20:00',
    createdAt: '2026-10-19 16:05:00'
  },
  {
    id: 7,
    type: 'LOST',
    status: 'PENDING',
    title: '遗落一副银色圆框眼镜',
    categoryId: 6,
    categoryName: '电子数码',
    location: '第二教学楼阶梯教室A',
    occurredAt: '2026-10-18 19:40:00',
    createdAt: '2026-10-18 21:02:00'
  },
  {
    id: 23,
    type: 'FOUND',
    status: 'CLAIMED',
    title: '食堂捡到一串钥匙挂小熊挂件',
    categoryId: 2,
    categoryName: '钥匙扣件',
    location: '第二食堂二楼',
    occurredAt: '2026-10-17 12:15:00',
    createdAt: '2026-10-17 12:48:00'
  },
  {
    id: 24,
    type: 'LOST',
    status: 'CLOSED',
    title: '弄丢了一把深蓝色折叠伞',
    categoryId: null,
    categoryName: null,
    location: '体育馆南门',
    occurredAt: '2026-10-16 18:05:00',
    createdAt: '2026-10-16 20:11:00'
  }
]

export function mockRecordPage(query: RecordQuery = {}): Promise<PageResult<RecordListItem>> {
  const page = query.page ?? 1
  const pageSize = query.pageSize ?? 10
  const total = mockRecords.length
  return Promise.resolve({
    page,
    pageSize,
    total,
    totalPages: total === 0 ? 0 : Math.ceil(total / pageSize),
    hasNext: page * pageSize < total,
    list: mockRecords
  })
}
