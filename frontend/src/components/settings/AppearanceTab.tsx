import { Radio, Space, Typography } from 'antd'
import { useTheme, type ThemeMode } from '../../theme/themeContext'

export default function AppearanceTab() {
  const { mode, setMode } = useTheme()
  return (
    <Space direction="vertical" size="middle">
      <Typography.Text type="secondary">主题偏好保存在本浏览器(localStorage),不随账号同步。</Typography.Text>
      <Radio.Group value={mode} onChange={(e) => setMode(e.target.value as ThemeMode)}>
        <Radio.Button value="light">亮色</Radio.Button>
        <Radio.Button value="dark">暗色</Radio.Button>
        <Radio.Button value="system">跟随系统</Radio.Button>
      </Radio.Group>
    </Space>
  )
}