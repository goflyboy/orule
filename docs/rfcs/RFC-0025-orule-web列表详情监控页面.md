# RFC-0025: orule-web 列表/详情/执行监控页面

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：4d · **阶段**：S6

---

## 1. 摘要

实现 orule-web 的规则管理列表页、规则详情页、执行监控 Dashboard。

---

## 2. 动机

- 规则管理是 orule 的核心用户界面（依据 `02-用例视图 §2.4`）
- 支撑 MVP 演示的完整用户流程

---

## 3. 详细设计

### 3.1 页面结构

```
orule-web/src/pages/
├── RuleListPage.tsx          # 规则列表
├── RuleDetailPage.tsx        # 规则详情（含编辑器入口）
├── RuleSetListPage.tsx       # 规则集列表
├── ExecutionDashboardPage.tsx # 执行监控
└── TestCasePage.tsx          # 测试用例管理

orule-web/src/components/
├── layout/
│   ├── AppLayout.tsx         # 整体布局
│   └── SideMenu.tsx          # 侧边菜单
├── rules/
│   ├── RuleTable.tsx         # 规则表格
│   ├── RuleStatusTag.tsx      # 状态标签
│   └── RuleCard.tsx           # 规则卡片
└── executions/
    ├── ExecutionTable.tsx     # 执行记录表
    ├── ExecutionStatusTag.tsx  # 执行状态标签
    └── ExecutionLogDrawer.tsx # 执行日志抽屉
```

### 3.2 规则列表页

```tsx
// orule-web/src/pages/RuleListPage.tsx

export function RuleListPage() {
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20 });
  const [filters, setFilters] = useState({ status: '', domainId: '' });

  const { data, isLoading } = useQuery({
    queryKey: ['rules', pagination, filters],
    queryFn: () => api.get('/api/v1/rules', { params: { ...pagination, ...filters } }),
  });

  return (
    <PageContainer title="规则列表">
      {/* 筛选栏 */}
      <div className="filter-bar">
        <Select
          placeholder="规则状态"
          onChange={v => setFilters(f => ({ ...f, status: v }))}
          options={[
            { label: '全部', value: '' },
            { label: '维护中', value: 'MAINTENANCE' },
            { label: '已发布', value: 'PUBLISHED' },
            { label: '已废弃', value: 'RETIRED' },
          ]}
        />
        <Select
          placeholder="领域"
          onChange={v => setFilters(f => ({ ...f, domainId: v }))}
          options={domains.map(d => ({ label: d.name, value: d.id }))}
        />
        <Button type="primary" icon={<PlusIcon />} onClick={() => navigate('/rules/new')}>
          新建规则
        </Button>
      </div>

      {/* 规则表格 */}
      <Table
        dataSource={data?.content ?? []}
        loading={isLoading}
        rowKey="id"
        pagination={{
          current: pagination.current,
          pageSize: pagination.pageSize,
          total: data?.total,
          onChange: (page, size) => setPagination({ current: page, pageSize: size }),
        }}
        columns={[
          { title: '规则名称', dataIndex: 'name', render: (name, record) => (
            <Link to={`/rules/${record.id}`}>{name}</Link>
          )},
          { title: '规则集', dataIndex: ['ruleSet', 'name'] },
          { title: '版本', render: (_, record) => record.latestVersion?.version ?? '-' },
          { title: '状态', dataIndex: 'latestVersion.status', render: status => (
            <RuleStatusTag status={status} />
          )},
          { title: '创建时间', dataIndex: 'createdAt', render: d => dayjs(d).format('YYYY-MM-DD HH:mm') },
          { title: '操作', render: (_, record) => (
            <Space>
              <Link to={`/rules/${record.id}/edit`}>编辑</Link>
              <Link to={`/rules/${record.id}/versions`}>版本</Link>
              <Link to={`/rules/${record.id}/test`}>测试</Link>
            </Space>
          )},
        ]}
      />
    </PageContainer>
  );
}
```

### 3.3 执行监控 Dashboard

```tsx
// orule-web/src/pages/ExecutionDashboardPage.tsx

export function ExecutionDashboardPage() {
  const { data: stats } = useQuery({
    queryKey: ['execution-stats'],
    queryFn: () => api.get('/api/v1/executions/stats'),
    refetchInterval: 30_000,  // 每 30s 刷新
  });

  return (
    <PageContainer title="执行监控">
      {/* 统计卡片 */}
      <Row gutter={16}>
        <Col span={6}>
          <StatCard
            title="今日执行总数"
            value={stats?.todayTotal ?? 0}
            trend={stats?.todayTrend}
          />
        </Col>
        <Col span={6}>
          <StatCard
            title="成功率"
            value={`${stats?.successRate ?? 0}%`}
            trend={stats?.successRateTrend}
            status={stats?.successRate > 95 ? 'success' : 'warning'}
          />
        </Col>
        <Col span={6}>
          <StatCard
            title="平均耗时"
            value={`${stats?.avgDurationMs ?? 0}ms`}
            status={stats?.avgDurationMs < 200 ? 'success' : 'warning'}
          />
        </Col>
        <Col span={6}>
          <StatCard
            title="失败数（今日）"
            value={stats?.todayFailed ?? 0}
            status={stats?.todayFailed > 10 ? 'error' : 'success'}
          />
        </Col>
      </Row>

      {/* 执行趋势图 */}
      <Card title="执行趋势（最近 7 天）" style={{ marginTop: 16 }}>
        <LineChart data={stats?.trend7d} />
      </Card>

      {/* 最近执行记录 */}
      <Card title="最近执行记录" style={{ marginTop: 16 }}>
        <ExecutionTable
          dataSource={stats?.recentExecutions ?? []}
          size="small"
        />
      </Card>
    </PageContainer>
  );
}
```

### 3.4 执行日志抽屉

```tsx
// orule-web/src/components/executions/ExecutionLogDrawer.tsx

export function ExecutionLogDrawer({
  executionId, visible, onClose
}: LogDrawerProps) {
  const { data: logs } = useQuery({
    queryKey: ['execution-logs', executionId],
    queryFn: () => api.get(`/api/v1/executions/${executionId}/logs`),
    enabled: visible,
  });

  return (
    <Drawer
      title="执行日志"
      placement="right"
      width={600}
      open={visible}
      onClose={onClose}
    >
      <Timeline>
        {logs?.map(log => (
          <Timeline.Item
            key={log.id}
            color={log.status === 'SUCCESS' ? 'green' : 'red'}
          >
            <p><b>{log.ruleId}</b> - {log.status}</p>
            <p>耗时: {log.durationMs}ms</p>
            {log.errorMessage && (
              <Alert type="error" message={log.errorMessage} showIcon />
            )}
            {log.createdAt && (
              <p style={{ color: '#999', fontSize: 12 }}>
                {dayjs(log.createdAt).format('YYYY-MM-DD HH:mm:ss')}
              </p>
            )}
          </Timeline.Item>
        ))}
      </Timeline>
    </Drawer>
  );
}
```

---

## 4. 影响面

- 新增 `orule-web/src/pages/` 和 `orule-web/src/components/rules/`、`executions/`
- 依赖 Ant Design + React Query
- 不涉及数据库

---

## 5. 测试计划

| 测试 | 方式 |
|------|------|
| 列表分页 | 分页切换正确 |
| 状态筛选 | 筛选结果正确 |
| 执行监控刷新 | 30s 自动刷新 |
| 日志抽屉 | 正确显示每条规则日志 |
| 错误展示 | failed 状态正确标记红色 |

---

## 6. 实施步骤

```
1. 创建页面结构
2. 实现 RuleListPage + RuleTable
3. 实现 RuleDetailPage（链接到 RuleEditor）
4. 实现 ExecutionDashboardPage
5. 实现 ExecutionLogDrawer
6. 配置路由
7. E2E 测试
```

---

## 7. 关联

- 上游：RFC-0016（规则 API）、RFC-0020（执行 API）
- 下游：RFC-0028（E2E 验收）
- ADR：—
