# RFC-0030: MVP 验收 + 演示 Demo

> **状态**：DRAFT · **优先级**：P0 · **预计工作量**：2d · **阶段**：S9

---

## 1. 摘要

编写 MVP 演示脚本，录制操作视频，准备发布检查清单。

---

## 2. 动机

- MVP 交付需要可演示的 Demo（依据 `09-收口与风险 §9.3.3`）
- 支撑团队内部评审和管理层汇报

---

## 3. 详细设计

### 3.1 演示脚本（demo-script.sh）

```bash
#!/usr/bin/env bash
# scripts/demo-script.sh
# MVP 演示脚本

set -e

API="http://localhost:8080/api/v1"
TRACE_ID="demo-$(date +%s)"

echo "===== orule MVP 演示开始 ====="
echo ""

# Step 1: 创建领域
echo "Step 1: 创建领域..."
DOMAIN=$(curl -s -X POST $API/domain-types \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: $TRACE_ID" \
  -d '{
    "code": "ORDER",
    "name": "订单域",
    "description": "订单相关规则"
  }')
DOMAIN_ID=$(echo $DOMAIN | jq -r '.data.id')
echo "✅ 领域创建成功: $DOMAIN_ID"

# Step 2: 创建规则集
echo ""
echo "Step 2: 创建规则集..."
RULESET=$(curl -s -X POST $API/rule-sets \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: $TRACE_ID" \
  -d "{
    \"code\": \"ORDER_DISCOUNT\",
    \"name\": \"订单折扣\",
    \"domainId\": \"$DOMAIN_ID\"
  }")
RULESET_ID=$(echo $RULESET | jq -r '.data.id')
echo "✅ 规则集创建成功: $RULESET_ID"

# Step 3: 创建规则
echo ""
echo "Step 3: 创建规则..."
RULE=$(curl -s -X POST $API/rules \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: $TRACE_ID" \
  -d "{
    \"ruleSetId\": \"$RULESET_ID\",
    \"code\": \"VIP_200_30\",
    \"name\": \"VIP 满 200 减 30\"
  }")
RULE_ID=$(echo $RULE | jq -r '.data.id')
echo "✅ 规则创建成功: $RULE_ID"

# Step 4: NL → SimpleTS
echo ""
echo "Step 4: NL → SimpleTS..."
SIMPLETS=$(curl -s -X POST $API/convert/nl-to-simplets \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: $TRACE_ID" \
  -d "{
    \"ruleId\": \"$RULE_ID\",
    \"naturalLanguage\": \"VIP 客户满 200 减 30\"
  }")
echo "✅ LLM 转换结果:"
echo "$SIMPLETS" | jq '.data.simpleTs' -r

# Step 5: 编译 SimpleTS
echo ""
echo "Step 5: 编译 SimpleTS..."
curl -s -X POST "$API/rule-versions/$VERSION_ID/compile" \
  -H "X-Trace-Id: $TRACE_ID"
echo "✅ 编译完成"

# Step 6: 发布
echo ""
echo "Step 6: 发布规则..."
curl -s -X POST "$API/rule-versions/$VERSION_ID/publish" \
  -H "X-Trace-Id: $TRACE_ID"
echo "✅ 发布成功"

# Step 7: 执行
echo ""
echo "Step 7: 执行规则..."
curl -s -X POST "$API/rules/$RULE_ID/execute" \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: $TRACE_ID" \
  -d '{
    "customer": { "tier": "VIP" },
    "order": { "totalAmount": 300, "discount": 0 }
  }' | jq '.data'

echo ""
echo "===== orule MVP 演示完成 ====="
```

### 3.2 发布检查清单

```markdown
# MVP 发布检查清单

## 功能检查
- [ ] 14 项 MVP 功能全部实现
- [ ] E2E 测试（RFC-0028）全部通过
- [ ] 0 个 P0 Bug

## 文档检查
- [ ] 9 份 4+1 视图完整
- [ ] 9 份 ADR 完整
- [ ] README + ARCHITECTURE 就绪
- [ ] RFC-0000 总览更新到最新状态

## 代码质量
- [ ] 单元测试覆盖率 ≥ 60%
- [ ] `mvn clean install` 成功
- [ ] `pnpm build` 成功
- [ ] Docker Compose 构建成功

## 安全检查
- [ ] Groovy 沙箱渗透测试通过
- [ ] OWASP Top 10 基础检查通过

## 性能检查
- [ ] 单条规则执行 P95 < 200ms
- [ ] 编译 P95 < 2s
- [ ] 72 小时长稳测试通过

## 演示准备
- [ ] Demo 脚本测试通过
- [ ] 演示视频录制完成
- [ ] 快速开始文档就绪
```

---

## 4. 关联

- 上游：全部 RFC
- 下游：—
- ADR：—
