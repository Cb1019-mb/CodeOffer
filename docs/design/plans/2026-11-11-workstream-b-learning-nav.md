# 工作流 B：学习页子路由重构 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 学习页改为「左侧目录 + 子路由分页」：URL 直达各类内容，目录带掌握度徽标，体检报告在总览渲染。

**Architecture:** ProjectLayout 持有 detail+task 轮询与全局横幅/头部/自测弹窗，子路由经 `useOutletContext` 取数据；不引入状态管理库。ResumeSection 一拆为二（STAR / 面试题）。

**Tech Stack:** React 19 · react-router-dom 7（嵌套路由 + Outlet context）· antd 6

**Spec:** `docs/design/specs/2026-11-11-multiuser-settings-navigation-refactor-design.md` §七

## Global Constraints

- 旧链接 `/projects/:id` 兼容（index 重定向 overview）。
- 轮询逻辑（3s、seq 守卫、终态停轮询）原样上移 Layout，行为不变。
- 跨用户/不存在项目：Layout 级错误页（后端 404 契约）。
- 徽标优先级：mastery≥3 绿对勾 → 否则 mastery==0 或到期 橙时钟 → 无。
- 前端 build + lint 零警告；浏览器实测 §7.4 边界表。

## 文件结构

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `frontend/src/components/project/ProjectLayout.tsx` | 新建 | 轮询/头部/横幅/目录/Outlet/自测弹窗 |
| `frontend/src/components/project/OverviewSection.tsx` | 新建 | 项目信息 + 体检报告 MD + 确认入库 |
| `frontend/src/components/project/CardSection.tsx` | 新建 | 按 :type 过滤卡片复用 CardList |
| `frontend/src/components/project/resumeShared.ts` | 新建 | Star/Qa 类型 + parseJson + 常量 |
| `frontend/src/components/project/ResumeStarSection.tsx` | 新建 | STAR 展示/编辑/重生成 |
| `frontend/src/components/project/InterviewSection.tsx` | 新建 | 面试题分级展示/重生成 |
| `frontend/src/components/ResumeSection.tsx` | 删除 | 被拆分取代 |
| `frontend/src/pages/ProjectPage.tsx` | 删除 | 被 ProjectLayout 取代 |
| `frontend/src/App.tsx` | 修改 | 嵌套路由 |

---

### Task B1: 路由与组件重构

**Files:** 见上表。

**Interfaces:**
- Outlet context：`{ projectId: number, detail: ProjectDetail, task: TaskView | null, refresh: () => Promise<ProjectDetail | null> }`
- 路由：`/projects/:id` → ProjectLayout；index→overview；`overview` / `cards/:type` / `resume` / `interview`。
- 目录 key：`overview`、`cards/ARCHITECTURE`…（6 类，大写枚举名作 URL 段）、`resume`、`interview`。

- [ ] **Step 1: resumeShared.ts**

```ts
export interface Star {
  situation?: string
  task?: string
  action?: string
  result?: string
  summary?: string
}

export interface Qa {
  level?: string
  question?: string
  answer?: string
}

export const LEVEL_LABEL: Record<string, string> = {
  base: '基础',
  advance: '进阶',
  deep: '深挖',
}

export const STAR_FIELDS: { key: keyof Star; label: string }[] = [
  { key: 'situation', label: 'S · 背景' },
  { key: 'task', label: 'T · 任务' },
  { key: 'action', label: 'A · 行动' },
  { key: 'result', label: 'R · 结果' },
  { key: 'summary', label: '一句话亮点' },
]

export function parseJson<T>(s: string | null | undefined, fallback: T): T {
  if (!s) return fallback
  try {
    return JSON.parse(s) as T
  } catch {
    return fallback
  }
}
```

- [ ] **Step 2: ProjectLayout.tsx**

要点（完整实现）：
- `useParams` id；`Number.isFinite` 校验；轮询 useEffect 与原 ProjectPage 相同（seq 守卫、3s、终态 clearInterval、refreshKey 重触发）。
- 错误且无 detail → 错误页（Alert + 返回按钮）；无 detail → Spin。
- 头部：返回 Link、项目名、状态 Tag、导出 Markdown（buildProjectMarkdown）、复制简历、整仓重生成 Popconfirm、开始自测按钮（QuizMode Modal）。
- RUNNING 进度 Card / FAILED Alert 全局横幅。
- `Layout` 内 `Sider`（breakpoint="lg" collapsedWidth=0）+ `Menu`：
  items = 总览 + 6 类卡片（label 带徽标 Icon：CheckCircleTwoTone green / ClockCircleTwoTone orange）+ STAR 简历 + 模拟面试题；
  selectedKeys 由 useLocation 推导（`/projects/:id/` 前缀剥离）；onClick navigate。
- 徽标函数：`badgeFor(type)` 取 detail.cards 中该 type 第一张：mastery>=3 → 绿；否则 mastery===0 或 (nextReview && nextReview<=now) → 橙；否则 null。
- `<Outlet context={{ projectId, detail, task, refresh }} />`。
- 内容区 `Layout.Content` padding 24，maxWidth 1080 居中。

- [ ] **Step 3: OverviewSection.tsx**

`useOutletContext<ProjectContext>()`；Descriptions（仓库地址/描述/技术栈）；`detail.healthReport` 非空 → Card「项目体检报告」+ MarkdownRenderer；status===REVIEW → 确认入库 Card（Button loading confirming → api.confirm → refresh）。

- [ ] **Step 4: CardSection.tsx**

`useParams().type` 大写化校验 ∈ 6 类枚举名；过滤 detail.cards；非法 → Empty「未知卡片类型」；空 → Empty「该类型卡片尚未生成」；否则 CardList（projectId, cards, onChanged=refresh）。标题用 card.typeLabel。

- [ ] **Step 5: ResumeStarSection.tsx / InterviewSection.tsx**

从原 ResumeSection 拆：STAR 部分（Descriptions + JSON 编辑 + 保存 + 重生成按钮）与面试题部分（分级 Collapse + 重生成按钮）。两者 resume 为 null → Empty。重生成调用 api.regenerateResume 后 refresh()。共享 resumeShared。

- [ ] **Step 6: App.tsx 嵌套路由 + 删除旧文件**

```tsx
<Route path="/projects/:id" element={<ProjectLayout />}>
  <Route index element={<Navigate to="overview" replace />} />
  <Route path="overview" element={<OverviewSection />} />
  <Route path="cards/:type" element={<CardSection />} />
  <Route path="resume" element={<ResumeStarSection />} />
  <Route path="interview" element={<InterviewSection />} />
</Route>
```

删除 `pages/ProjectPage.tsx`、`components/ResumeSection.tsx`。

- [ ] **Step 7: build + lint**

Run: `cd frontend && npm run build && npm run lint`
Expected: 零错误零警告。

- [ ] **Step 8: 提交**

```bash
git add frontend/src/
git commit -m "feat(frontend): 学习页左侧目录+子路由分页,掌握度徽标与体检报告总览渲染"
```

---

### Task B2: 浏览器实测与收尾

- [ ] **Step 1: 重启前端无需（Vite HMR）；后端已在跑。浏览器登录 demo_mb。**
- [ ] **Step 2: 黄金路径**：首页 → 查看有卡片项目 → 默认落 overview（体检报告可见）→ 目录逐类点击（URL 变化、内容正确、徽标显示）→ 卡片编辑/采纳按钮在 → resume 编辑入口 → interview 分级列表 → 自测弹窗 → 导出 Markdown。
- [ ] **Step 3: 边界表**：直达 `/projects/999999/overview` 错误页；FAILED 项目（608）横幅+空态；直达未生成类型 URL 空态；生成中切换子路由轮询不中断（可用 608 重生成观察，注意限流间隔）；旧链接 `/projects/606` 重定向 overview。
- [ ] **Step 4: 检查 scripts/e2e.ps1 覆盖面**：若其断言旧单页结构则更新选择器到新路由；跑通。
- [ ] **Step 5: 提交（如有 e2e 改动）**

## 验收（对照 spec §八 B 条）

- [ ] 全部子路由直达可用；徽标准确；边界表全过；旧链接兼容
- [ ] build + lint 零警告；e2e（如适用）绿