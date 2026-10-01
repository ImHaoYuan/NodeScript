## 11. 安全模型（来源分级）

| 来源 | 信任级别 | 引擎 | 能力 | 说明 |
|---|---|---|---|---|
| 内置/作者签名模板 | 高 | Node | 全量（含 root） | 软签名验证 |
| 用户自写脚本 | 中 | Node | 全量 | 提示风险 |
| 第三方/市场脚本 | **低** | **Node（与自写脚本同进程、同权）** | **全量** —— **沙箱已裁，进程隔离这条防线不存在**（§18 第 1 项 2026-09-26） | 防线改为：**安装时按 `hasInstallScript` 如实告知并由用户当场选**（§18 第 7 项）+ 来源分级提示（本表）+ 运行期 TTL/看门狗；**文案不得让人误以为是沙箱跑的** |
| 打包分发脚本 | 中高 | Node | 打包时配置 | 走签名链 |

- RuntimeChannel/engines 通信也按来源分级（低信任不能给高信任发控制消息）。
- 桥的 `ModuleRegistry` 按 scripts 的 CapabilityMask 过滤 handler（非授权模块调用 → `ERR_PERMISSION_DENIED`，不是静默 no-op）。

---

### 11.1 资产与信任边界

先说三条不可从别处推出来的事实，后面所有防线都挂在它们上：

| # | 事实 | 出处 |
|---|---|---|
| 1 | **进程隔离这条防线不存在**。引擎只有 Node 一条轨，第三方脚本与用户自写脚本**同进程、同权**（无障碍读屏/点击/截屏/网络/文件全开）。原先「第三方 → QuickJS 白名单子集」的隔离随 QuickJS 整轨撤出而作废 | §18 第 1 项（[18-19-ledger.md](18-19-ledger.md)）+ [design-decisions.md](../design-decisions.md) 已拍板第 1 项 |
| 2 | **因此能力授予是唯一边界，不是进程边界**。给用户的提示必须直说「装来的脚本与自写脚本同权」，不能让文案读起来像沙箱 | 同上 |
| 3 | **桥面按来源与能力双重过滤**：RuntimeChannel/engines 通信按来源分级（低信任不能给高信任发控制消息）；`ModuleRegistry` 按脚本的 CapabilityMask 过滤 handler，未授权调用回 `ERR_PERMISSION_DENIED`（不是静默 no-op） | 本节上方两条 bullets + §7 |

**要守的资产**（按被攻破后的代价排序）：① 设备上的账号与凭据（脚本可读 `filesDir` 与已授权能力所见的一切）；② 宿主 App 自身的完整性（脚本能改自己的项目文件，从而改下一次启动时跑什么）；③ 已授予的自动化能力（点击、输入、截屏可被脚本用于对外操作）；④ 用户注意力（脚本可弹窗/发通知/驱动界面）。

**信任边界只有三条，其余都是边界内的**：脚本 ↔ 桥（跨进程异步，按 uid 与 CapabilityMask 校验）；脚本 ↔ npm 供应链（lock/registry/安装脚本）；宿主 ↔ 用户（权限授予与安装选择）。

### 11.2 威胁与防线对照

下表左列是本设计**认为会发生**的攻击面，右列是**当前实际生效**的防线与其落点。凡落点为空的行，见 §11.3 残余风险，不假装已覆盖。

| # | 威胁（攻击者能力） | 攻击面 | 现行防线（落点） |
|---|---|---|---|
| T1 | **恶意/被污染的包在安装时执行任意代码**（lifecycle 脚本） | npm 安装 | 零 spawn 主路径：T0 全程 `--ignore-scripts`，安装脚本一个都没跑过，回执 `scripts-skipped`（`InstallCoordinator`，§10.5-3）。**残余**：让脚本真跑的那条路（spawn 桥）尚未落地，见 §11.3 |
| T2 | **lockfile 投毒**（把包名指到别处，integrity 仍成立） | `npm ci` 重建 | `LockSigner` 对 lock 做 HMAC-SHA256 带外签名（`files/.autojs/lock.sig`，键绑 `projectId` 防跨项目搬锁），`ci` 前验签；缺签名/错签名一律 `ERR_PERMISSION_DENIED`（TOFU 自签不算通过）。**接线现状（2026-10-01 核实）**：防线代码与单测都在，但**生产装配没接** —— `NpmShellKit.assembleHandler` 的 `lockKey` 缺省 `null` → 既不签也不验，全仓无 `KeyProvider` 实现。见 §11.3 第 8 条 |
| T3 | **单一镜像/注册表投毒** | registry 响应 | `NpmRegistryVerifier` 双运营主体交叉校验（第二意见必须与首选**不同运营主体**，同站即自比、自比一律拒）；取不到/非 https/无 integrity 锚点一律 `Verdict.Unverifiable` 由调用方显式告知，不折成「通过」 |
| T4 | **冒名客户端抢绑桥 socket** | abstract unix socket | 桥监听与客户端 `main.cpp` 对称验 peer uid（`SO_PEERCRED`），凭据读不到即 fail-closed 拒收（`BridgeSocketListener`）；abstract 名带 uid 后缀，多实例互不抢绑 |
| T5 | **未授权能力被调用**（脚本绕过能力授予） | 桥面 | `ModuleRegistry` 按 CapabilityMask 过滤 handler → `ERR_PERMISSION_DENIED`；`PermissionCenter` 是唯一权限入口，读取异常诚实降级 `DEGRADED`（可用性未知即受限），绝不伪造 `GRANTED` |
| T6 | **脚本失控/僵死/赖活**（死循环、OOM、宿主与引擎状态分裂） | 引擎进程 | `EngineWatchdog` 按心跳/RSS/CPU 采样 + drift 连段裁决（`KillCause.DRIFT`）+ 执行期限（`KillCause.TIMEOUT`）；心跳必须由引擎进程自己打点，看门狗只问不记账 |
| T7 | **安装期互踩/打爆磁盘/恶意 git 依赖/无限挂起** | 安装会话 | per-project 锁 + 全局互斥、free ≥500MB 预检、项目 512MB 配额（80% 黄 / 100% 拦）、`git:` 依赖入口即拒 `ERR_NOT_SUPPORTED`、安装 TTL（`InstallCoordinator`） |
| T8 | **审批票重放**（拿旧版本 APPROVED 装新版本） | 审批账本 | 审批键绑 `pkg + versionHash`，宿主从盘上重算版本哈希，命中 `APPROVED` 才放行（`ApprovalLedger` + `requestApprove`/`requestGateApproval`）；未获批自请入队 `PENDING` |
| T9 | **本地状态被系统备份/换机迁移带到另一个设备上下文**（Auto Backup、D2D 迁移、`adb backup`） | 应用私有目录 `files/.autojs/` | `AndroidManifest` 置 `android:allowBackup="false"`（`:app`）：信任锚与审计面不进备份 —— `lock.sig` 到新机验不过（密钥不出本机），而审批台账/安装 history/意图日志会以「已完成/已批准」的姿态跟着走，恢复出来的状态不可信也不可解释。代价如实说：换机不自动带走过往审批与历史，需重装/重批 |

### 11.3 残余风险（诚实缺口，未被上面任何一条覆盖）

1. **进程隔离不存在** —— 见 §11.1 第 1 条。这是 §18 第 1 项拍板的直接后果，不是待修的 bug。
2. **安装脚本「一个都没真跑过」**：T0 全程 `--ignore-scripts`，回执 `scripts-skipped`。等 spawn 桥（P1）落地后，「用户选择跑」这条才有落点；**在那之前，安装脚本永不执行**（§18 第 7 项 + §10.5-3）。
3. **`lock.sig` 是本地信任锚，不是第三方可验证**：签名用应用私钥，只能证明「这份 lock 是本机签过的」，不构成跨设备/跨用户的可验证来源证明。应用私钥丢失 = 显式「安全降级」失败（`LockSigner` KDoc 口径），不静默放行。**密钥从哪来：目前没有实现** —— 接缝是 `LockSigner.KeyProvider`，设计口径是 Android Keystore 包装的应用密钥，但全仓没有任何 `KeyProvider` 实现、生产装配传 `null`（见第 8 条），所以「生产走 Keystore」这句现在是**目标形态**，不是现状。
4. **MediaProjection 高清会话未落**：授权 UI + FGS 那一档还没接，P0 由同一 a11y 帧源连续截图承接（§9.2）。这不是安全缺口，是能力边界，列此只为避免被当成「高清会话已有门禁」。
5. **16KB 页机未测**：真机红测只有 16KB 模拟器镜像或 Pixel 8+ 能给，SELinux enforcing 上下文与 targetSdk 提取策略同样待真机（design-status「仍未验」块）。
6. **审批卡呈现层未排期**：审批账本与桥面拉取口已通（`drainApprovals` → `NpmBridgeHandler` → JS `pumpApprovals`），但能力中心的审批卡不在当前排期内，期间审批只能靠脚本侧拉取。
7. **上报流程缺失**：本仓当前没有对外的安全问题上报渠道，见根 [`SECURITY.md`](../../SECURITY.md)。
8. **npm 生产装配未接线（2026-10-01 核实）**：`AppShellKit` 调 `NpmShellKit.assembleHandler(filesDir, cacheDir)` 走全缺省 —— `executor = HeavyOpExecutor.Unavailable`（真机安装如实回 `ERR_NOT_IMPLEMENTED`，`HostNodeExecutor` 在 `app/src/main` 零引用）、`lockKey = null`（T2 的签/验与快照导出都不发生）、`scriptExecutor = Unavailable`（T1 门禁过了也跑不起来）。即：**设计上写着「已接线」的那几道 npm 防线，当前在生产路径上都不生效**；这是接线缺口，不是设计缺口。

### 11.4 非目标

- 本节不引入新的防线，只**登记现有防线与已知缺口**；新增/加强防线走 §18 决策台账，不在设计文档里悄悄添。
- 不做「威胁模型的完备性」论证——上表是按攻击者可达路径枚举的，不是 STRIDE 全矩阵逐格推导。
- 打包（APK 重打包/签名）的密钥管理与签名向导随整轨移入后续版本（§13），其密钥口径不在本节展开。
