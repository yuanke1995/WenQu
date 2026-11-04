// 工作区图标/颜色：新建弹窗、工作区页设置、侧栏三处共用一份，避免各抄一张表后图标名对不上。
import {
  FolderOutlined, FolderOpenOutlined, CoffeeOutlined, ExperimentOutlined, RobotOutlined,
  AimOutlined, AppstoreOutlined, SettingOutlined, ThunderboltOutlined, BookOutlined,
  CodeOutlined, DatabaseOutlined, FileTextOutlined, StarOutlined
} from '@ant-design/icons-vue'

export const WORKSPACE_ICONS = [
  { name: 'folder', label: '文件夹', cmp: FolderOutlined, open: FolderOpenOutlined },
  { name: 'coffee', label: '咖啡', cmp: CoffeeOutlined, open: CoffeeOutlined },
  { name: 'experiment', label: '实验', cmp: ExperimentOutlined, open: ExperimentOutlined },
  { name: 'robot', label: '机器人', cmp: RobotOutlined, open: RobotOutlined },
  { name: 'aim', label: '目标', cmp: AimOutlined, open: AimOutlined },
  { name: 'appstore', label: '应用', cmp: AppstoreOutlined, open: AppstoreOutlined },
  { name: 'setting', label: '设置', cmp: SettingOutlined, open: SettingOutlined },
  { name: 'thunderbolt', label: '闪电', cmp: ThunderboltOutlined, open: ThunderboltOutlined },
  { name: 'book', label: '书', cmp: BookOutlined, open: BookOutlined },
  { name: 'code', label: '代码', cmp: CodeOutlined, open: CodeOutlined },
  { name: 'database', label: '数据库', cmp: DatabaseOutlined, open: DatabaseOutlined },
  { name: 'file', label: '文档', cmp: FileTextOutlined, open: FileTextOutlined },
  { name: 'star', label: '星标', cmp: StarOutlined, open: StarOutlined }
]

const ICON_MAP = Object.fromEntries(WORKSPACE_ICONS.map(i => [i.name, i]))

/** 图标名 → 组件；空/未知一律回落到文件夹（展开态给打开的文件夹，与旧侧栏一致） */
export const workspaceIconOf = (name, open = false) => {
  const ic = ICON_MAP[name]
  if (!ic) return open ? FolderOpenOutlined : FolderOutlined
  return open ? ic.open : ic.cmp
}

export const WORKSPACE_COLORS = [
  '#3f7f5f', '#2f6fed', '#f08a24', '#f0b429', '#e5484d', '#d6338c', '#7b4fd1',
  '#4f66d1', '#1f9ec9', '#2aa198', '#6b7280', '#111827', '#b39ddb', '#5cb85c'
]
