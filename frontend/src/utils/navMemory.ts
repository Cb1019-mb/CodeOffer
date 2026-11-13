// 板块导航记忆:项目区与设置区各自记住最近访问的完整路径(含查询参数),
// 顶栏菜单在两大板块间切换时回到上一步的页面。sessionStorage 按标签页隔离。
const PROJECT_KEY = 'codeoffer-nav-project'
const SETTINGS_KEY = 'codeoffer-nav-settings'

export function getProjectPath(): string {
  return sessionStorage.getItem(PROJECT_KEY) || '/'
}

export function getSettingsPath(): string {
  return sessionStorage.getItem(SETTINGS_KEY) || '/settings'
}

export function rememberPath(path: string): void {
  if (path.startsWith('/settings')) {
    sessionStorage.setItem(SETTINGS_KEY, path)
  } else if (path === '/' || path.startsWith('/projects/')) {
    sessionStorage.setItem(PROJECT_KEY, path)
  }
}

/** 项目删除后清掉指向它的记忆路径,避免菜单回到已不存在的项目。 */
export function forgetProjectPath(id: number): void {
  const saved = getProjectPath()
  if (saved === `/projects/${id}` || saved.startsWith(`/projects/${id}/`)) {
    sessionStorage.setItem(PROJECT_KEY, '/')
  }
}