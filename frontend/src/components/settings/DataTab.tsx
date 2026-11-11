import { useEffect, useState } from 'react'
import { Alert, Button, Card, Popconfirm, Space, Statistic, message } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import { useAuth } from '../../auth/authContext'

export default function DataTab() {
  const { user } = useAuth()
  const [info, setInfo] = useState<{ sizeBytes: number; repoCount: number } | null>(null)
  const isAdmin = user?.role === 'ADMIN'

  useEffect(() => {
    if (isAdmin) {
      api.storageInfo().then(setInfo).catch(() => setInfo(null))
    }
  }, [isAdmin])

  const backup = async () => {
    try {
      await api.backupDownload()
      message.success('备份已开始下载')
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const clear = async () => {
    try {
      const r = await api.clearRepoCache()
      message.success(`已清理 ${r.removed} 个克隆缓存`)
      setInfo(await api.storageInfo())
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%', maxWidth: 560 }}>
      <Card size="small" title="我的数据备份">
        <Space direction="vertical">
          <span>导出当前用户全部项目、知识卡片与简历产出为 JSON(不含他人数据)。</span>
          <Button icon={<DownloadOutlined />} onClick={backup}>下载备份 JSON</Button>
        </Space>
      </Card>
      {isAdmin && (
        <Card size="small" title="克隆缓存(管理员)">
          {info ? (
            <Space direction="vertical" style={{ width: '100%' }}>
              <Space size="large">
                <Statistic title="缓存仓库数" value={info.repoCount} />
                <Statistic title="占用字节" value={info.sizeBytes} />
              </Space>
              <Alert type="info" showIcon message="清理不影响已生成产物;下次重新生成会自动重新克隆。" />
              <Popconfirm title="清理全部克隆缓存?" okText="清理" okButtonProps={{ danger: true }} onConfirm={clear}>
                <Button danger>清理缓存</Button>
              </Popconfirm>
            </Space>
          ) : (
            <span>缓存信息加载失败</span>
          )}
        </Card>
      )}
    </Space>
  )
}