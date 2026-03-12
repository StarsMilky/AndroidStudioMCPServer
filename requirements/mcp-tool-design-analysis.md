# MCP Tool 设计分析：上下文爆炸与信息冗余问题

## 问题 1：大型项目中的上下文爆炸

### 基准假设

- 大型 Android 项目：**500+ 类，20+ 模块，50+ 第三方依赖**
- AI 上下文窗口：**128K-200K tokens**（Claude/GPT-4 级别）
- 单次 MCP 调用返回的合理预算：**< 2K tokens（约 1500 字/4KB）**
- 单轮对话中 MCP 总返回预算：**< 15K tokens**（留大量空间给代码和推理）

### 现有 27 个 Tool 的返回量级评估

#### 符号级工具（1-10）— 大多数没问题

| Tool | 500 类项目的典型返回量 | 评估 |
|------|----------------------|------|
| `resolve_symbol` | ~100B（1 个类型 + 1 个位置） | ✅ 极小 |
| `find_usages` | 10-1000 条 × 100B = **1KB-100KB** | 💥 热门类可能爆 |
| `get_available_symbols` | 50-300 个符号 × 80B = **4-24KB** | ⚠️ 大作用域下偏大 |
| `rename_symbol` | 5-50 个被修改文件 × 100B = 0.5-5KB | ✅ 可控 |
| `analyze_nullability` | ~200B | ✅ 极小 |
| `trace_data_flow` | 3-10 步路径 × 100B = 0.3-1KB | ✅ 小 |
| `get_type_hierarchy` | 5-30 个子类 × 80B = 0.4-2.4KB | ✅ 可控 |
| `get_call_hierarchy(depth=3)` | 最坏 N^3 条 = **几十 KB** | 💥 指数膨胀 |
| `structural_search` | 0-500 个匹配 × 150B = **0-75KB** | 💥 可能爆 |
| `run_and_fix` | 50-500 个问题 × 200B = **10-100KB** | 💥 会爆 |

#### 项目级工具（P1-P10）— 几乎全部有问题

| Tool | 500 类项目的典型返回量 | 评估 |
|------|----------------------|------|
| `get_project_architecture` | 20 模块 × 10 包 × 20 类详情 = **200-500KB** | 💥💥 必爆 |
| `get_dependency_graph` | 500 节点 × 平均 5 条边 × 100B = **250KB** | 💥💥 必爆 |
| `analyze_change_impact` | 受 depth 限制，hop=3 时 50-200 项 × 100B = 5-20KB | ⚠️ 偏大 |
| `get_framework_view(room)` | 10 Entity × 字段 + 5 DAO × 查询 = **5-30KB** | ⚠️ 中等 |
| `get_framework_view(hilt)` | 全 DI 图 = **10-50KB** | ⚠️ 偏大 |
| `check_architecture_rules` | 违规数通常有限 = 1-5KB | ✅ 可控 |
| `find_dead_code` | 可能数百个死代码 × 100B = **10-50KB** | 💥 会爆 |
| `find_code_clones` | 克隆组 × 实例 = **20-100KB** | 💥 会爆 |
| `get_code_health` | 500 类的指标 = **100KB+** | 💥💥 必爆 |
| `detect_patterns` | 模式实例列表 = **10-50KB** | ⚠️ 偏大 |
| `get_module_api_surface` | 一个大模块的所有 public 符号 = **10-30KB** | ⚠️ 偏大 |
| `analyze_error_handling` | 所有 try-catch 分析 = **10-50KB** | 💥 可能爆 |

#### 非 PSI 工具（11-17）

| Tool | 典型返回量 | 评估 |
|------|-----------|------|
| `create_checkpoint` | ~50B | ✅ 极小 |
| `rollback_to_checkpoint` | ~100B | ✅ 极小 |
| `get_file_history` | 5-20 条 × 100B = 0.5-2KB | ✅ 小 |
| `render_layout` | Base64 图片 = **50-500KB** | 💥💥 必须特殊处理 |
| `resolve_resource_value` | ~500B | ✅ 小 |
| `run_scratch` | 取决于输出 = 0.1-10KB | ⚠️ 不可控 |
| `decompile_class` | 完整源码 = **1-30KB** | ⚠️ 大类会爆 |
| `get_external_annotations` | ~200B | ✅ 极小 |

### 结论

**27 个 Tool 中有 12 个在大型项目中会爆上下文，6 个偏大需要控制。** 项目级工具几乎全部有问题。

---

## 问题 2：信息冗余分析

### 冗余关系图

```
get_project_architecture ──┬── 包含模块依赖 ───── 与 get_dependency_graph 重复
                           ├── 包含类分布 ─────── 与 get_code_health 重复
                           └── 包含包结构 ─────── 与 get_module_api_surface 重复

find_usages ───────────────── depth=1 时 ─────── 等价于 get_call_hierarchy(depth=1)

get_call_hierarchy ────────── callers 方向 ────── 与 find_usages 信息子集重复
                               callees 方向 ────── 与 trace_data_flow(forward) 部分重复

get_type_hierarchy ────────── inheritors ───────── 与 find_usages(override类型) 重复

get_dependency_graph ──────── 违规边 ────────────── 与 check_architecture_rules 重复
                               节点度数 ────────── 与 get_code_health(coupling) 重复

get_code_health ───────────── 反模式 ────────────── 与 detect_patterns(anti_patterns) 重复
                               死代码指标 ────────── 与 find_dead_code 重复

analyze_change_impact ─────── 影响路径 ────────── 是 get_dependency_graph 的子图查询

get_framework_view ────────── 类分类 ────────────── 与 get_project_architecture 部分重复

structural_search ─────────── 反模式搜索 ────────── 与 detect_patterns 功能重复
                               克隆搜索 ────────── 与 find_code_clones 功能重复
```

### 冗余严重度

| 冗余组 | 涉及的 Tool | 严重度 |
|--------|------------|--------|
| 引用/调用/层级三合一 | `find_usages` + `get_call_hierarchy` + `get_type_hierarchy` | 🔴 高 |
| 架构/依赖/API 表面三合一 | `get_project_architecture` + `get_dependency_graph` + `get_module_api_surface` | 🔴 高 |
| 质量/健康/模式/死代码四合一 | `get_code_health` + `detect_patterns` + `find_dead_code` + `find_code_clones` | 🔴 高 |
| 依赖图/影响/架构规则三合一 | `get_dependency_graph` + `analyze_change_impact` + `check_architecture_rules` | 🟡 中 |
| 搜索/克隆/反模式重叠 | `structural_search` + `find_code_clones` + `detect_patterns` | 🟡 中 |
| 数据流/调用层级 | `trace_data_flow` + `get_call_hierarchy(callees)` | 🟢 低（角度不同） |

### 结论

**27 个 Tool 中存在至少 5 组严重冗余，实际独立信息源只有约 12-15 个。**

---

## 重新设计：精简后的 MCP Tool 集

### 设计原则

1. **每个 Tool 有唯一信息价值**——不与其他 Tool 返回重复信息
2. **固定上限**——每次返回 < 4KB（约 2K tokens），通过分页/摘要控制
3. **渐进式披露**——先返回摘要，AI 需要时再请求详情
4. **查询式而非倾倒式**——Tool 回答具体问题，不做 data dump

### 精简后：12 个 MCP Tool

---

#### Tool 1: `resolve_symbol` — 符号解析（保留，无变化）

**唯一信息：** 指定位置的精确类型和声明位置。

**返回量：** ~100B，恒定。无爆炸风险。

```
输入: (file, line, column)
输出: { type: "MyAdapter<Item>", declaration: "MyAdapter.kt:15", kind: "class" }
```

---

#### Tool 2: `find_references` — 统一的引用/调用/层级查询

**合并了：** `find_usages` + `get_call_hierarchy` + `get_type_hierarchy`

**唯一信息：** 一个符号被谁使用、继承、调用——三者本质都是"引用关系"的不同维度。

**防爆设计：**
- 默认 `limit=20`，最大 100
- 返回摘要行（文件+行号+代码片段），不返回完整代码
- `depth` 参数控制层级深度

```
输入: {
  file, line, column,
  kind: "usages" | "callers" | "callees" | "inheritors" | "supers",
  depth: 1,        // 1=直接引用, 2+=递归展开
  limit: 20,       // 最多返回条数
  group_by: "file"  // 按文件分组去重
}

输出: {
  total: 47,          // 总数（不受 limit 影响）
  returned: 20,       // 本次返回数
  results: [
    { file: "MainFragment.kt", line: 87, code: "viewModel.getData()", kind: "call" },
    ...
  ]
}
```

**返回量：** 20 条 × ~120B = ~2.4KB。恒定可控。

**为什么要合并？** AI 问"谁调用了 getData"时，不在乎底层是 `find_usages` 还是 `call_hierarchy`。统一入口减少了 AI 选择工具的认知成本，也消除了返回重复信息的可能。

---

#### Tool 3: `get_scope` — 作用域可用符号查询（保留，加限制）

**唯一信息：** 指定代码位置可以使用的所有符号（变量、方法、类型）。

**防爆设计：**
- `filter` 参数过滤类型
- 默认只返回**名称和类型**，不返回完整签名
- `limit=50`

```
输入: {
  file, line, column,
  filter: "variables" | "methods" | "types" | "all",
  limit: 50
}

输出: {
  total: 156,
  returned: 50,
  symbols: [
    { name: "viewModel", type: "MainViewModel", source: "class_member" },
    { name: "binding", type: "FragmentMainBinding", source: "local" },
    ...
  ]
}
```

**返回量：** 50 条 × ~60B = ~3KB。可控。

---

#### Tool 4: `refactor` — 安全重构（保留，合并多种重构）

**合并了：** `rename_symbol` + `safe_delete` + `move_symbol` + `extract_method` + `change_signature`

**唯一信息：** 语义级代码变换的执行结果。

**防爆设计：**
- 返回受影响文件列表（不返回修改内容，文件已被修改可以直接读取）
- `dry_run` 模式只返回影响摘要

```
输入: {
  action: "rename" | "move" | "extract_method" | "safe_delete" | "change_signature",
  file, line, column,
  params: { new_name: "newName" },  // 根据 action 不同
  dry_run: false
}

输出: {
  success: true,
  affected_files: ["A.kt", "B.xml", "AndroidManifest.xml"],
  changes_count: 12,
  warnings: []
}
```

**返回量：** ~500B-2KB。可控。

---

#### Tool 5: `analyze_data_flow` — 数据流分析（合并空安全和值追踪）

**合并了：** `analyze_nullability` + `trace_data_flow` + `get_external_annotations`

**唯一信息：** 值的来源、去向、可能状态（null/非null/值范围）。与 `find_references` 的区别：references 找"谁在语法上引用了这个符号"，data_flow 追踪"这个值从哪来、到哪去、在每个点上是什么状态"。

**防爆设计：**
- 只返回关键路径节点，不展开所有分支
- `max_steps` 限制追踪深度

```
输入: {
  file, line, column,
  query: "nullability" | "value_sources" | "value_consumers",
  max_steps: 5
}

输出: {
  // nullability 查询
  nullability: "possibly_null",
  reason: "Nullable return from UserDao.findById() at line 23",
  path: [
    { file: "UserDao.kt", line: 23, status: "nullable", code: "fun findById(): User?" },
    { file: "Repository.kt", line: 45, status: "not_checked", code: "val user = dao.findById(id)" }
  ]
}
```

**返回量：** ~500B-2KB。可控。

---

#### Tool 6: `query_project` — 项目结构查询（自适应全景 + 按需钻取）

**合并了：** `get_project_architecture` + `get_dependency_graph` + `get_module_api_surface` + `get_active_variant_context`

**唯一信息：** 项目的静态结构——模块、包、类、依赖关系、API 边界。

**核心设计：自适应全景（Adaptive Panorama）+ Detail Query（按需钻取）**

**关键洞察：不同规模的项目，"全景图"的最佳粒度不同。** 小项目可以直接返回到方法级别的完整类图，大项目只能压缩到模块级摘要。插件应根据项目规模自动选择最丰富的信息粒度。

---

**不同规模项目的全景图数据量实测：**

```
┌──────────────────┬────────────┬─────────────────────────────────────────────────────────────┬──────────────┐
│ 规模              │ 典型项目    │ 全景图包含什么                                               │ 大小          │
├──────────────────┼────────────┼─────────────────────────────────────────────────────────────┼──────────────┤
│ 微型 (< 20 类)    │ Demo/练手   │ 所有类 + 所有 public 方法签名 + 字段 + 类间关系 + 框架详情      │ ~3-5KB       │
│                  │            │ → 相当于完整的 UML 类图                                       │ ~2K tokens   │
│                  │            │                                                             │              │
│ 小型 (20-80 类)   │ 单模块应用  │ 所有类 + 所有 public 方法签名 + 类间继承/依赖关系 + 框架详情      │ ~8-15KB      │
│                  │            │ → 相当于省略了私有方法的完整类图                                 │ ~5-8K tokens │
│                  │            │                                                             │              │
│ 中型 (80-300 类)  │ 多模块应用  │ 所有模块 + 所有包 + 所有类(名+类型+可见性+实现) + 框架列表       │ ~12-20KB     │
│                  │            │ → 相当于只有类签名没有方法的类图                                 │ ~6-10K tokens│
│                  │            │                                                             │              │
│ 大型 (300-800 类) │ 企业级应用  │ 模块级摘要 + 包结构 + 框架指纹 + 关键枢纽类 Top10               │ ~3-5KB       │
│                  │            │ → 模块级架构图（即之前的 Digest 方案）                           │ ~2-3K tokens │
│                  │            │                                                             │              │
│ 超大 (800+ 类)    │ 超级应用    │ 模块分组 + 模块摘要 + 框架指纹 + 关键枢纽类 Top10               │ ~4-6KB       │
│                  │            │ → 带 Feature 聚类的模块架构图                                  │ ~2-3K tokens │
└──────────────────┴────────────┴─────────────────────────────────────────────────────────────┴──────────────┘
```

**核心结论：设定 ~16KB（约 8K tokens）的全景图预算上限，不同规模的项目都能在这个预算内给到最丰富的信息。**

---

**插件侧自适应逻辑（伪代码）：**

```kotlin
fun generatePanorama(project: Project, budget: Int = 16_000): ProjectPanorama {
    val classCount = countProjectClasses(project)
    val moduleCount = countModules(project)

    // 从最丰富的级别开始尝试，找到预算内最优解
    val levels = listOf(
        Level.FULL_MEMBERS,    // 含方法签名 + 字段
        Level.CLASS_SIGNATURES,// 含类名 + 类型 + 可见性 + 实现关系
        Level.CLASS_NAMES,     // 仅类名 + 类型
        Level.MODULE_DIGEST,   // 仅模块级
        Level.GROUPED_DIGEST   // 模块分组
    )

    for (level in levels) {
        val estimated = estimateSize(project, level)
        if (estimated <= budget) {
            return buildPanorama(project, level)
        }
    }
    return buildPanorama(project, Level.GROUPED_DIGEST)
}
```

AI 端无需关心分级逻辑，**只需调用 `query_project(mode="panorama")`，插件自动返回预算内最丰富的信息。**

---

**各级别的输出示例：**

```
// ===== 微型/小型项目：Level.FULL_MEMBERS =====
// 包含方法签名和字段，相当于完整 UML 类图
// 一次调用就能看懂整个项目的所有 API

输入: { mode: "panorama" }
输出: {
  level: "full_members",    // 告知 AI 当前是哪个精度级别
  project_classes: 35,

  // 模块 + 依赖（同 Digest）
  modules: [{ name: "app", type: "android-app", classes: 35 }],
  dependencies: {},
  architecture: "Single-module MVVM with Room + Retrofit",
  entry_points: ["MainActivity(@AndroidEntryPoint)"],
  variant: { name: "debug", build_type: "debug" },

  // 框架详情（直接展开，不需要再查 query_framework）
  frameworks: {
    "room": {
      "entities": [
        {
          "name": "UserEntity", "table": "users",
          "fields": [
            { "name": "id", "type": "Long", "primary_key": true },
            { "name": "name", "type": "String" },
            { "name": "email", "type": "String?" }
          ]
        }
      ],
      "daos": [
        {
          "name": "UserDao",
          "methods": [
            "getAll(): Flow<List<UserEntity>>",
            "findById(id: Long): UserEntity?",
            "insert(user: UserEntity)",
            "delete(user: UserEntity)"
          ]
        }
      ]
    },
    "retrofit": {
      "services": [
        {
          "name": "UserApi",
          "endpoints": [
            "GET /users → List<UserDto>",
            "GET /users/{id} → UserDto",
            "POST /users (CreateUserRequest) → UserDto"
          ]
        }
      ]
    }
  },

  // 完整类图（含方法签名）
  class_map: {
    "com.example.ui": [
      {
        "name": "MainActivity",
        "kind": "class",
        "extends": "AppCompatActivity",
        "annotations": ["@AndroidEntryPoint"],
        "members": [
          "onCreate(savedInstanceState: Bundle?)",
          "setupRecyclerView()",
          "observeViewModel()"
        ]
      },
      {
        "name": "MainViewModel",
        "kind": "class",
        "extends": "ViewModel",
        "annotations": ["@HiltViewModel"],
        "members": [
          "users: StateFlow<List<User>>",
          "loadUsers()",
          "deleteUser(id: Long)",
          "refreshData()"
        ]
      }
    ],
    "com.example.data": [
      {
        "name": "UserRepository",
        "kind": "interface",
        "members": [
          "getUsers(): Flow<List<User>>",
          "getUserById(id: Long): User?",
          "saveUser(user: User)"
        ]
      },
      {
        "name": "UserRepositoryImpl",
        "kind": "class",
        "implements": ["UserRepository"],
        "visibility": "internal",
        "injected_deps": ["UserDao", "UserApi", "UserMapper"]
      }
    ],
    "com.example.model": [
      {
        "name": "User",
        "kind": "data class",
        "fields": ["id: Long", "name: String", "email: String?", "avatarUrl: String?"]
      }
    ]
  }
}

// ===== 中型项目：Level.CLASS_SIGNATURES =====
// 有类签名但没有方法详情

输入: { mode: "panorama" }
输出: {
  level: "class_signatures",
  project_classes: 156,

  modules: [
    { name: "app",    type: "android-app",  classes: 68 },
    { name: "domain", type: "kotlin-lib",   classes: 28 },
    { name: "data",   type: "android-lib",  classes: 42 },
    { name: "core",   type: "kotlin-lib",   classes: 18 }
  ],
  dependencies: {
    "app": ["domain", "data", "core"],
    "data": ["domain", "core"],
    "domain": ["core"],
    "core": []
  },
  architecture: "Clean Architecture (3-layer)",
  entry_points: ["MyApp(@HiltAndroidApp)", "MainActivity"],
  variant: { name: "debug" },

  frameworks: {
    "room":     { entities: 5, daos: 4 },
    "retrofit": { services: 2, endpoints: 10 },
    "hilt":     { modules: 4 },
    "compose":  { screens: 8 }
  },

  // 类签名列表（不含方法）
  class_map: {
    "com.example.domain.model": [
      { "name": "User",    "kind": "data class" },
      { "name": "Post",    "kind": "data class" },
      { "name": "Comment", "kind": "data class" }
    ],
    "com.example.domain.repository": [
      { "name": "UserRepository",    "kind": "interface" },
      { "name": "PostRepository",    "kind": "interface" }
    ],
    "com.example.domain.usecase": [
      { "name": "GetUsersUseCase",   "kind": "class", "deps": ["UserRepository"] },
      { "name": "GetPostsUseCase",   "kind": "class", "deps": ["PostRepository"] },
      { "name": "LoginUseCase",      "kind": "class", "deps": ["AuthRepository"] }
    ],
    "com.example.data.repository": [
      { "name": "UserRepositoryImpl",  "kind": "class", "implements": "UserRepository", "visibility": "internal" },
      { "name": "PostRepositoryImpl",  "kind": "class", "implements": "PostRepository", "visibility": "internal" }
    ],
    "com.example.app.ui.home": [
      { "name": "HomeFragment", "kind": "class", "extends": "Fragment" },
      { "name": "HomeViewModel","kind": "class", "extends": "ViewModel", "deps": ["GetUsersUseCase", "GetPostsUseCase"] }
    ]
    // ... 其他包的类列表
  }
}

// ===== 大型项目：Level.MODULE_DIGEST =====
// 即之前的 Digest 方案，只有模块级信息，无类列表

输入: { mode: "panorama" }
输出: {
  level: "module_digest",
  project_classes: 487,

  modules: [
    { name: "app",          type: "android-app",  classes: 87,  packages: 8  },
    { name: "domain",       type: "kotlin-lib",   classes: 34,  packages: 4  },
    { name: "data",         type: "android-lib",  classes: 56,  packages: 6  },
    { name: "core-ui",      type: "android-lib",  classes: 28,  packages: 3  },
    { name: "core-network", type: "kotlin-lib",   classes: 15,  packages: 2  }
  ],
  dependencies: { ... },
  architecture: "Clean Architecture",
  entry_points: [...],
  variant: { ... },
  frameworks: { ... },
  key_classes: [
    { name: "UserRepository",  module: "domain",  afferent: 12, role: "core-abstraction" },
    { name: "MainViewModel",   module: "app",     efferent: 9,  role: "orchestrator" }
  ]
  // 注意：没有 class_map，因为装不下
}

// ===== 超大项目：Level.GROUPED_DIGEST =====

输入: { mode: "panorama" }
输出: {
  level: "grouped_digest",
  project_classes: 1200,

  module_groups: [
    { group: "Auth",    modules: ["auth-api","auth-impl","auth-ui"],    classes: 89  },
    { group: "Payment", modules: ["pay-core","pay-gw","pay-ui"],       classes: 120 },
    { group: "Social",  modules: ["social-api","social-impl","social-ui"], classes: 95 },
    { group: "Core",    modules: ["core-net","core-db","core-ui","core-common"], classes: 180 },
    { group: "App",     modules: ["app"],                                classes: 156 }
  ],
  // 模块级信息同 MODULE_DIGEST，但按 group 组织
  dependencies_between_groups: {
    "App": ["Auth", "Payment", "Social", "Core"],
    "Auth": ["Core"],
    "Payment": ["Core"],
    "Social": ["Core"],
    "Core": []
  },
  architecture: "Modularized by feature, Clean Architecture per feature",
  key_classes: [...],
  frameworks: { ... }
}
```

---

**Detail Query（按需钻取）仍然保留：**

Panorama 给出全景，但 AI 要深入某个模块/包/类时仍需 Detail：

```
// 在大型项目中，AI 看到 panorama 后想深入 data 模块
输入: { mode: "detail", target: "module:data" }
输出: { 包列表 + 公共 API 类列表 }                               ~1-2KB

// 在中型项目中，AI 看到 panorama 后想看某个类的方法详情
// （因为 CLASS_SIGNATURES 级别没有包含方法）
输入: { mode: "detail", target: "class:UserRepository" }
输出: { 方法签名列表 + 继承关系 + 注解 }                          ~500B-1KB

// 在小型项目中，panorama 已经包含了方法签名，
// AI 几乎不需要 detail 查询——一次 panorama 就够了
```

---

**返回量保证：**

| 项目规模 | panorama 大小 | 上限 | AI 是否需要 detail 查询 |
|---------|-------------|------|----------------------|
| 微型 < 20 类 | ~3-5KB | 16KB | 几乎不需要——panorama 已含全部方法签名 |
| 小型 20-80 类 | ~8-15KB | 16KB | 很少——只在需要看私有方法时 |
| 中型 80-300 类 | ~12-20KB | 16KB | 偶尔——需要看某个类的方法详情时 |
| 大型 300-800 类 | ~3-5KB | 16KB | 经常——需要钻取模块/包内类列表 |
| 超大 800+ 类 | ~4-6KB | 16KB | 频繁——需要逐层钻取 |

**插件侧优化：**
- Panorama 在 Gradle Sync 后预计算并缓存到内存
- 首次调用 `query_project(mode="panorama")` 即时返回（~0ms）
- 文件变更时通过 VFS Listener 增量更新缓存
- 输出中包含 `level` 字段，告知 AI 当前精度级别，AI 据此决定是否需要 detail 查询

---

#### Tool 7: `query_framework` — 框架视图查询（同样改为 Digest + Detail）

**合并了：** `get_framework_view(room/retrofit/hilt/compose)`

**唯一信息：** 框架注解驱动的结构化视图——这是纯靠读代码拼不出来的。

**设计思路同 query_project：** 框架 Digest 已包含在 Project Digest 的 `frameworks` 字段中（概要数据）。`query_framework` 只在 AI 需要**某个框架的详细信息**时调用。

```
// 列表模式——一次看清所有实体/接口（~1.5KB）
输入: { framework: "room", detail: "list" }
输出: {
  entities: [
    { name: "UserEntity",    table: "users",    fields: 6, relations: 2 },
    { name: "PostEntity",    table: "posts",    fields: 8, relations: 1 },
    { name: "CommentEntity", table: "comments", fields: 5, relations: 1 }
  ],
  daos: [
    { name: "UserDao",    queries: 6 },
    { name: "PostDao",    queries: 4 },
    { name: "CommentDao", queries: 3 }
  ]
}

// 单项详情——深入一个实体/DAO（~1KB）
输入: { framework: "room", detail: "entity", target: "UserEntity" }
输出: {
  table_name: "users",
  fields: [
    { name: "id", type: "Long", primary_key: true },
    { name: "email", type: "String", nullable: true, index: true }
  ],
  relations: [{ type: "one-to-many", target: "PostEntity", via: "userId" }],
  dao_methods: ["getAll()", "findById(id)", "insert(user)", "delete(user)"]
}
```

**注意：** 框架的概要统计（"Room: 8 entities, 5 DAOs"）已经在 `query_project(digest)` 中返回了。`query_framework` 只在 AI 需要**看具体有哪些 Entity、哪些 API endpoint** 时才调用，避免信息重复。

**返回量：** list ~1.5KB, detail ~1KB。可控。

---

#### Tool 8: `analyze_quality` — 代码质量分析（合并 + TopN）

**合并了：** `get_code_health` + `detect_patterns` + `find_dead_code` + `find_code_clones` + `analyze_error_handling`

**唯一信息：** 代码质量问题——复杂度、耦合度、死代码、克隆、反模式、异常处理。

**核心设计：只返回 Top N 最严重的问题，不做全量 dump**

```
输入: {
  scope: "project" | "module" | "file",
  target: "app",
  aspects: ["complexity", "dead_code", "clones", "error_handling", "anti_patterns"],
  top_n: 10
}

输出: {
  overall_score: 67,
  top_issues: [
    {
      rank: 1,
      aspect: "complexity",
      target: "PaymentProcessor",
      file: "PaymentProcessor.kt",
      detail: "Cyclomatic complexity 47 (threshold: 15), 42 methods",
      suggestion: "Split into PaymentValidator, PaymentExecutor"
    },
    {
      rank: 2,
      aspect: "dead_code",
      target: "OldUserMapper",
      file: "OldUserMapper.kt",
      detail: "87 lines, zero references in project",
      suggestion: "Safe to delete"
    },
    {
      rank: 3,
      aspect: "error_handling",
      target: "NetworkRepository.fetchData()",
      file: "NetworkRepository.kt:89",
      detail: "Empty catch block swallows IOException",
      suggestion: "Log and rethrow or handle explicitly"
    },
    ...
  ],
  summary: {
    dead_classes: 5,
    dead_methods: 23,
    clone_groups: 8,
    swallowed_exceptions: 12,
    god_classes: 2
  }
}
```

**返回量：** 10 条 × ~200B + 摘要 ~300B = ~2.3KB。恒定可控。

---

#### Tool 9: `check_rules` — 架构规则校验（保留，本身就小）

**合并了：** `check_architecture_rules`（吸收了 `get_dependency_graph` 的违规检测）

**唯一信息：** 违规列表——哪些代码违反了架构约定。与 `query_project` 的区别：project 返回结构事实，rules 返回违规判断。

**返回量：** 违规通常有限，~1-3KB。可控。

---

#### Tool 10: `structural_search` — 结构化搜索（保留，加限制）

**唯一信息：** 按自定义 AST 模式搜索代码。与 `find_references` 的区别：references 搜索特定符号的引用，structural_search 搜索任意代码模式。与 `analyze_quality` 的区别：quality 用预定义规则评估，structural_search 让 AI 自定义搜索模式。

**防爆设计：** `limit=20`

```
输入: {
  pattern: "Thread($arg$).start()",
  scope: "project",
  limit: 20
}

输出: {
  total: 7,
  matches: [
    { file: "SyncService.kt", line: 45, code: "Thread { doSync() }.start()" },
    ...
  ]
}
```

**返回量：** 20 条 × ~100B = ~2KB。可控。

---

#### Tool 11: `checkpoint` — Local History 操作（保留，合并）

**合并了：** `create_checkpoint` + `rollback_to_checkpoint` + `diff_with_checkpoint` + `get_file_history`

**唯一信息：** 独立于 Git 的细粒度版本控制。

```
输入: {
  action: "create" | "rollback" | "diff" | "history",
  label: "before-refactoring",
  file: "可选"
}
```

**返回量：** create/rollback ~100B，history ~1-2KB，diff ~2-4KB（可截断）。可控。

---

#### Tool 12: `sandbox` — 执行沙盒（合并 Scratch + 反编译 + 渲染）

**合并了：** `run_scratch` + `decompile_class` + `render_layout` + `convert_java_to_kotlin` + `run_and_fix`

**唯一信息：** "执行一个操作，返回结果"——运行代码、反编译、渲染布局、转换语言、批量修复。这些本质都是"输入 → 处理 → 输出"的动作型工具。

**防爆设计：**
- `run_code`：限制 stdout 输出长度（`max_output: 2000`）
- `decompile`：只返回前 N 行 + 摘要（`max_lines: 100`）
- `render`：返回图片 URL（非 base64 内嵌），或只返回渲染状态/警告
- `convert_j2k`：单文件，输出量 = 输入量，可接受
- `batch_fix`：只返回摘要（fixed/unfixed 数量），不返回修改内容

```
输入: {
  action: "run_code" | "decompile" | "render_layout" | "convert_j2k" | "batch_fix",
  // 各 action 的特有参数
}

输出: {
  // run_code
  stdout: "输出内容（截断到 max_output）",
  stderr: "",
  exit_code: 0,
  truncated: false

  // decompile
  source_preview: "前 100 行...",
  total_lines: 350,
  artifact: "com.squareup.retrofit2:retrofit:2.9.0"

  // batch_fix
  fixed: 42,
  unfixable: 5,
  unfixable_summary: ["需要人工判断的 5 项的简要描述"]
}
```

**返回量：** 各 action 均 < 4KB。可控。

---

## 精简前后对比

### 数量变化

| | 精简前 | 精简后 | 变化 |
|--|--------|--------|------|
| Tool 总数 | 27 | 12 | -56% |
| 会爆上下文的 Tool | 12 | 0 | -100% |
| 存在冗余的 Tool 组 | 5 组 | 0 组 | -100% |

### 信息正交性验证

每个 Tool 返回的核心信息不与其他 Tool 重复：

| Tool | 返回什么（唯一信息） | 绝对不返回什么 |
|------|--------------------|--------------| 
| `resolve_symbol` | 一个位置的精确类型 | 不返回引用列表、不返回类成员 |
| `find_references` | 一个符号的引用列表 | 不返回类型信息、不返回代码健康度 |
| `get_scope` | 一个位置可用的符号列表 | 不返回引用关系、不返回类型层级 |
| `refactor` | 重构执行结果（受影响文件） | 不返回引用列表、不返回代码内容 |
| `analyze_data_flow` | 值的来源/去向/null 状态 | 不返回引用列表、不返回类型信息 |
| `query_project` | 项目结构事实（模块/包/依赖） | 不返回代码质量评估、不返回框架语义 |
| `query_framework` | 框架注解驱动的语义视图 | 不返回依赖关系、不返回代码质量 |
| `analyze_quality` | Top N 代码质量问题 | 不返回项目结构、不返回框架视图 |
| `check_rules` | 架构规则违规列表 | 不返回结构事实、不返回质量指标 |
| `structural_search` | 自定义 AST 模式匹配结果 | 不返回质量评估、不返回引用关系 |
| `checkpoint` | 版本快照操作结果 | 不返回代码内容、不返回分析结果 |
| `sandbox` | 执行操作的输出结果 | 不返回分析数据、不返回结构信息 |

### 返回量保证

| Tool | 最大返回量 | 机制 |
|------|-----------|------|
| `resolve_symbol` | ~200B | 恒定 |
| `find_references` | ~3KB | `limit=20` 硬上限 |
| `get_scope` | ~3.5KB | `limit=50` 硬上限 |
| `refactor` | ~2KB | 只返回文件列表 |
| `analyze_data_flow` | ~2KB | `max_steps=5` 限制 |
| `query_project` | Panorama ≤16KB / Detail ~2KB | 自适应全景：小项目含方法级类图，大项目压缩为模块摘要 |
| `query_framework` | list ~1.5KB / detail ~1KB | 框架概要已含在 Digest 中；此工具只返回详情 |
| `analyze_quality` | ~3KB | `top_n=10` 硬上限 |
| `check_rules` | ~3KB | 违规数自然有限 |
| `structural_search` | ~3KB | `limit=20` 硬上限 |
| `checkpoint` | ~4KB | diff 可截断 |
| `sandbox` | ~4KB | 各 action 均有截断 |

**单次调用最大返回：4KB。AI 连续调用 5 次工具 = 最多 20KB ≈ 10K tokens。** 占 128K 上下文窗口的不到 8%。安全。

---

## AI 的典型调用路径示例

### 场景：AI 接手一个陌生项目并修改一个功能

```
Step 1: query_project(mode="digest")
        → 完整项目画面：5 模块, Clean Architecture, 模块依赖图,
          框架指纹(Room 8表/Retrofit 3服务/Hilt), 关键枢纽类,
          当前 Variant=freeDebug
        → AI 一次调用就拿到"整张地图"                               ~3.5KB

Step 2: resolve_symbol(file="UserRepo.kt", line=23, col=12)
        → "UserRepository 是 interface, 声明在 domain 模块"
        → AI 对照 digest 中的模块图，理解这是核心抽象层              ~100B

Step 3: find_references(kind="inheritors", limit=5)
        → "有 1 个实现类 UserRepositoryImpl 在 data 模块"            ~200B

Step 4: get_scope(file="UserRepoImpl.kt", line=45)
        → "这里可以用 apiService, database, mapper 等"              ~2KB

Step 5: checkpoint(action="create", label="before-change")
        → "OK"                                                     ~50B

Step 6: [AI 修改代码——此时 AI 心中有完整的项目地图，知道改动在哪个层级]

Step 7: find_references(kind="callers", target=修改的方法)
        → "3 个 UseCase 调用了它，需要检查兼容性"                     ~500B

Total MCP 返回: ~6.4KB ≈ 3.2K tokens。极其安全。

关键区别：Step 1 之后 AI 就拥有了完整的项目心智模型，
后续所有操作都在这张"地图"的指引下进行，不需要反复查询项目结构。
```

### 场景：AI 评估代码质量并做清理

```
Step 1: analyze_quality(scope="project", top_n=5)
        → "Top 5 问题：1 个 God Class, 3 个死方法, 1 个吞异常"       ~1.5KB

Step 2: find_references(target=死方法, kind="usages")
        → "0 个引用，确认是死代码"                                    ~100B

Step 3: refactor(action="safe_delete", target=死方法, dry_run=true)
        → "安全，无冲突"                                             ~100B

Step 4: refactor(action="safe_delete", target=死方法)
        → "已删除，影响 1 个文件"                                     ~100B

Total MCP 返回: ~1.8KB。极小。
```
