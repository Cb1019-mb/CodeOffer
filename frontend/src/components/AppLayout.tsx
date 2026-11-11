import { Layout, Menu, Dropdown, Button, Tag, Space } from 'antd'
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { useAuth } from '../auth/authContext'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const onLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Header style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
        <Link to="/" style={{ color: '#fff', fontSize: 18, fontWeight: 600, whiteSpace: 'nowrap' }}>
          CodeOffer
        </Link>
        <Menu
          theme="dark"
          mode="horizontal"
          selectedKeys={[location.pathname === '/' ? 'home' : 'other']}
          items={[{ key: 'home', label: <Link to="/">项目</Link> }]}
          style={{ flex: 1, minWidth: 0 }}
        />
        <Dropdown
          menu={{
            items: [
              { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: onLogout },
            ],
          }}
        >
          <Button type="text" style={{ color: '#fff' }}>
            <Space>
              <UserOutlined />
              {user?.username}
              {user?.role === 'ADMIN' && <Tag color="gold">管理员</Tag>}
            </Space>
          </Button>
        </Dropdown>
      </Layout.Header>
      <Layout.Content>
        <Outlet />
      </Layout.Content>
    </Layout>
  )
}