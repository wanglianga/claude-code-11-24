# 音乐版权使用归属与版税结算服务

面向结算员的版权结算后端：把平台上报的每一次使用匹配到**正确的录音版本与权利人**，
按**使用当日有效合同**计算应付款，并对每一笔暂扣、冲突与调整给出可追溯解释。
技术栈：Java 25、Spring Boot 3.5、PostgreSQL 18、Flyway、springdoc-openapi。
金额一律以 **BIGINT 最小货币单位**（如美分）存储与运算，份额全程使用**有理数（分子/分母）**，
分摊采用**最大余数法**保证逐笔金额之和严格等于可分配额。

> 本服务为后端工程，不访问真实银行：付款产出本地 CSV 付款文件，回执由模拟银行文件导入核验。
> OpenAPI 3 文档与 Swagger UI 随应用提供。

---

## 1. 领域规则如何落地

| 业务要求 | 实现位置与做法 |
| --- | --- |
| 相同曲名有不同录音版本，词曲权与录音权收款人不同 | `sound_recording` 以明确录音标识（类 ISRC）为唯一归属锚点；词曲合同挂 `work_id`、录音合同挂 `recording_id`，两类权利各自独立选合同、独立出应付 |
| 平台内部编号与版权目录不通用 | `platform_mapping` 维护平台编号→录音的人工审核映射；`mapping_candidate` 仅保存标题/作者/时长候选，**任何相似度都不能自动归属**，无 APPROVED 映射的款项进 `UNMATCHED` 待归属，不猜收款人 |
| 合同份额季度中途改变 | 合同带 `valid_from/valid_to`、地区、媒介、权利类型；`CatalogRepository.effectiveContracts` 严格按**使用日期**选取，旧款不会被最新份额重算 |
| 份额合计超 100% 或多份相互冲突的有效合同 | `ShareResolver`：合计>100% 整笔 `CONFLICT_HOLD` 阻止结算；多份并存合同中跨合同出现的权利人份额暂扣，**无争议部分继续**；不足 100% 的剩余单列 `UNCLAIMED`，绝不放大已知人比例 |
| 按次单价 vs 直接报告收入两种方式 | `platform_agreement.settlement_method` 声明 `PER_UNIT`/`GROSS_REPORT`，由 `IncomeCalculator` 实现；按次=有效次数×当期单价，直接收入=已核对原币净额；同一行两种方式混用直接阻止 |
| 平台费用只扣一次 | 净额已含扣费则直接取净额（`REPORTED_NET`）不重扣；无净额时仅按协议明确条款对总额扣一次（`REPORTED_GROSS_LESS_FEE`）；按次行携带平台总额会被拒绝，防止“次数乘单价又重复分总额” |
| 报告批次原始合计、坏行/重复行/有效行分别计数 | 报告文件 `#BATCH` 头声明合计；`ReportImportService` 输出 valid/bad/dup 计数，声明与明细不符或存在坏行即转 `RECONCILING`，**只有 ACCEPTED 批次能进入结算**，不漏行却自称完整 |
| 撤销关联原报告行、重传与晚到更正 | 以平台原行编号+修订号辨识；同修订号重传只计重复不落库、原件不覆盖；高修订号 NORMAL 标记原件 SUPERSEDED，REVOKE 标记原件 REVOKED；晚到更正形成新计算批次 |
| 逐笔守恒、尾差分配 | `Rational` + `LargestRemainder`：每结算行写入后硬断言分摊合计=可分配额；批次级再断言分摊闭合且挂账台账与非应付分摊一致 |
| 不同权利类型预算分列 | 直接收入按协议预算份额（如录音 60%/词曲 40%）拆分；预算合计不足 100% 的余额记 `UNBUDGETED` 待认领，词曲和录音都不能领走整笔 |
| 采用前预览影响金额 | `GET /api/candidates/{id}/preview` 与 `/api/mappings/preview` 实时计算影响金额且不落库，返回守恒校验与告警 |
| 批准后才生成付款文件，重试内容不变 | `PaymentService` 保存账户快照与正文 SHA-256 指纹，请求标识由正文确定性派生；重复调用复用原运行与原文件 |
| 超时查原标识、不重发；回执金额+币种+账户一致才记已付 | `query-timeout` 只回查原 `request_id`；回执核验分 MATCHED/MISMATCH_AMOUNT/MISMATCH_CURRENCY/MISMATCH_ACCOUNT/UNKNOWN_REQUEST/DUPLICATE；旧账户按生成时快照核对 |
| 结算批次固定依据、复核撤回 | `SnapshotService` 对结算行源指纹、选中合同及份额、录音、分摊结果生成指纹；`review` 额外显式检查映射撤回/改派、录音撤出、原报告行撤销 |
| 已批准季度的权利更正 | 差额单 `adjustment_order` 留痕，批准后另起 `ADJUSTMENT` 批次出账，**旧批次金额不变** |
| 中断续算、同范围不可重复出账 | 分段（默认 200 行/段）独立事务；`settlement_lock` 唯一约束 + `ScopeLockManager`（REQUIRES_NEW 独立事务）保证同范围双执行者拦截 |
| 应收可展开到合同与平台原行 | `GET /api/payees/{id}/receivable` 逐级钻取：批次→结算行→合同维度→平台原行编号/修订号 |

## 2. 工程结构

```
src/main/java/cc/royalty
├── common/        Rational 有理数、最大余数法、SHA-256 指纹、CSV、领域异常
├── domain/        不可变领域记录
├── repo/          JdbcTemplate 仓储（无 ORM 魔法，SQL 显式可审）
├── service/       导入、候选匹配、收入确定、份额解析、结算引擎、快照、付款回执、差额单、查询
├── web/           REST 控制器与全局异常处理
└── config/        事务模板、OpenAPI 配置
src/main/resources/db/migration/V1__init.sql   全部表结构（Flyway 自动迁移）
samples/          权利人/作品/录音/合同/协议/映射/平台报告/模拟回执样本
```

## 3. 本地启动

### 3.1 一键启动（Docker Compose，推荐）

```bash
docker compose up --build
# 应用: http://localhost:8080
# Swagger UI: http://localhost:8080/swagger-ui.html
# OpenAPI:    http://localhost:8080/v3/api-docs
```

Compose 内含 PostgreSQL 18（库/用户/密码 `ccdev`），Flyway 启动时自动建表到 `royalty` schema。

### 3.2 本机直跑（使用容器/本机已装 PostgreSQL 18）

```bash
# 数据库默认连接 127.0.0.1:5432/ccdev（ccdev/ccdev），可用环境变量覆盖
/opt/maven/bin/mvn -B spring-boot:run
# 或先打包
/opt/maven/bin/mvn -B package -DskipTests
/opt/jdk-25/bin/java -jar target/royalty-settlement-1.0.0.jar
```

环境变量：`DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD DB_SCHEMA PAYMENT_DIR RECEIPT_DIR`。

### 3.3 账号说明

- 数据库账号：`ccdev / ccdev`（隔离开发库，非生产配置）。
- 应用无登录体系，业务操作人通过接口 `actor` 参数留痕（默认 `settler`，复核示例用 `reviewer-li`）。

## 4. 验证方式（可复现脚本）

### 4.1 自动化测试

```bash
/opt/maven/bin/mvn -B test
```

测试使用独立 schema `royalty_test`（`src/test/resources/application-test.yml`），每个用例自动清表。

### 4.2 HTTP 全流程（应用启动后）

```bash
B=http://localhost:8080; S=samples
# 1) 目录与合同
for f in payees works contributors recordings platforms contracts agreements mappings; do
  curl -s -F "file=@$S/$f.csv" $B/api/import/$f; echo
done
# 2) 平台报告（含坏行/重复行核对批次、晚到更正批次）
curl -s -F "file=@$S/reports/q1_plfa.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/q1_plfb.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/q1_plfc.csv"      $B/api/import/reports
curl -s -F "file=@$S/reports/recon_bad_dup.csv" $B/api/import/reports   # 期望 reconciliation=MISMATCH
curl -s -F "file=@$S/reports/late_correction.csv" $B/api/import/reports # 修订/撤销原件
# 3) 候选与待匹配（只出候选，不自动归属）
curl -s -X POST $B/api/batches/1/candidates
curl -s "$B/api/workbench/pending-matching?batchId=1"
# 4) 结算（platformId 可查 /api/import 后用 SQL：select id from platform where platform_code='PLF-A'）
curl -s -X POST $B/api/settlements -H 'Content-Type: application/json' \
  -d '{"platformId":1,"periodStart":"2026-01-01","periodEnd":"2026-03-31","actor":"settler-zhang"}'
curl -s $B/api/settlements/1/summary                 # 守恒汇总与挂账解释
# 5) 复核批准 → 付款文件 → 模拟回执
curl -s -X POST $B/api/settlements/1/review
curl -s -X POST "$B/api/settlements/1/approve?actor=reviewer-li"
curl -s -X POST "$B/api/settlements/1/payment-runs?currency=USD"
curl -s $B/api/payment-runs/PR00000001-USD           # 取 request_id/账户快照后制作回执
curl -s -F "file=@signed-receipt.csv" $B/api/import/receipts
curl -s $B/api/workbench/receipt-failures
```

## 5. 样本覆盖的关键场景（`samples/`）

- **同名不同录音**：`Midnight Echo` 有 Original/Live 两个录音（SR-ME-ORIG、SR-ME-LIVE），候选分数 Original 0.995 > Live 0.85，但必须人工采用。
- **季度中途转权**：SR-ME-ORIG 录音权 2026-02-16 起从 RecordCo Alpha 转至 RecordCo Beta（RC-ME-1/RC-ME-2 日期不重叠）。
- **多合同争议**：SR-SS-1 两份并存合同，跨合同的 Alpha 份额暂扣、Beta 无争议继续。
- **份额超 100%**：SR-CT-1 有效份额合计 150%，整笔阻止结算。
- **份额不足 100%**：SR-OT-1 仅 75% 有主，25% 待认领；PLF-C 预算合计 90%，余 10% 待认领。
- **两种结算方式**：PLF-A 按次单价（含 10% 费用条款）、PLF-B 直接净额（60/40 预算）、PLF-C 只报总额（15% 条款扣一次）。
- **坏行/重复行/撤销/晚到更正**：`recon_bad_dup.csv`、`late_correction.csv`。
- **部分付款与回执失败**：测试内构造金额/币种/账户/未知标识四类失败回执。

## 6. 已执行的验证结论

- `mvn test`：**7 个测试全部通过**（5 个有理数/LRM 单元测试 + 2 个端到端/续算集成测试），覆盖总额守恒、未知款不分派、中途转权、冲突暂扣、坏行重复行、晚到更正、幂等付款、四类回执失败、差额单、撤回拦截、分段续算。
- 打包 jar 实际启动成功，Flyway 自动迁移通过；Swagger UI 返回 200，OpenAPI 暴露 32 个接口路径。
- 真实 HTTP 冒烟完成全部导入→候选→结算→复核→批准→付款文件→回执核验流程；重复结算范围返回 400；付款重试返回 `reused=true` 且文件指纹不变；金额不符回执进入失败清单。
- 付款文件生成于 `data/payment-files/<runNo>/`，内容含账户快照与正文指纹，不随账户后改或重试变化。

## 7. 关键约束与边界

- 不连接真实银行、不发起真实支付；`payment_request` 为模拟指令，回执必须由文件导入。
- 结算只采信 `APPROVED` 映射与 `ACCEPTED` 报告批次；`PROPOSED` 映射、`RECONCILING` 批次不产生款项。
- 所有金额计算在应用层以整数/有理数完成，数据库仅存储，杜绝浮点误差。
