import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import 'antd/dist/reset.css'
import App from './App'
import { AppearanceProvider, useAppearance } from './appearance'
import { AuthProvider } from './auth'
import './fonts.css'
import './index.css'

function Themed() {
  const { themeConfig } = useAppearance()
  return (
    <ConfigProvider locale={zhCN} theme={themeConfig}>
      <AntApp>
        <AuthProvider>
          <App />
        </AuthProvider>
      </AntApp>
    </ConfigProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AppearanceProvider>
      <Themed />
    </AppearanceProvider>
  </React.StrictMode>,
)
