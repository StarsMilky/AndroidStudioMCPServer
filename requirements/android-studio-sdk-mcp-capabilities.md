# Android Studio SDK 独占能力评估 — MCP Server 可暴露能力清单

## 筛选标准

本文档严格按照以下三个条件筛选：

1. **Android Studio SDK（IntelliJ Platform SDK）有对应 API**
2. **Bash / PowerShell 命令无法实现**（排除 `adb`、`gradle`、`git`、`aapt2`、`apkanalyzer` 等 CLI 工具可完成的能力）
3. **对 AI Agent 有实际价值**（排除"AI 自己就能做到"或"收益不大"的能力）

---

## 排除清单

### A. CLI 可完成（不需要 IDE SDK）

| 能力 | CLI 替代方案 |
|------|-------------|
| Logcat 日志读取 | `adb logcat` |
| 设备列表/属性 | `adb devices -l` |
| 设备截图 | `adb exec-out screencap -p` |
| APK 安装/卸载 | `adb install` / `adb uninstall` |
| 设备文件系统浏览 | `adb pull` / `adb push` / `adb shell ls` |
| Gradle 构建/编译 | `./gradlew assembleDebug` |
| Gradle 依赖树 | `./gradlew dependencies` |
| Android Lint 检查 | `./gradlew lint`（生成 HTML/XML 报告） |
| APK 分析 | `apkanalyzer` CLI |
| 代码格式化 | `ktfmt` / `google-java-format` CLI |
| 静态分析 | `detekt` / `ktlint` / `spotbugs` CLI |
| 签名 | `apksigner` / `jarsigner` |
| Manifest 解析 | `aapt2 dump badging` |
| 单元/插桩测试 | `./gradlew test` / `connectedAndroidTest` |
| Compose Preview 截图 | `./gradlew updateDebugScreenshotTest`（AGP 9.0+） |

### B. AI 自己就能做到（不需要 IDE SDK 辅助）

| 能力 | 为什么 AI 不需要 IDE 帮忙 |
|------|-------------------------|
| Intention Action（意图操作） | AI 直接改代码即可，不需要 IDE 的"转换为表达式体"等预设操作 |
| 代码生成（override/constructor/equals） | AI 读懂类结构后自己生成的质量不亚于 IDE 模板 |
| 实时代码分析（DaemonCodeAnalyzer） | 对 AI 来说，Gradle 编译慢几十秒不是关键瓶颈 |
| Editor 状态（光标位置/选中文本） | Agent 工作流中通常不依赖光标位置 |
| Run Configuration 管理 | CLI 命令组合可完成部署和启动 |
| Debugger 操作（断点/求值） | AI Agent 的调试范式是读日志 + 读代码 + 推理，不是单步调试 |
| Inspection 检测（不含修复） | `./gradlew lint` + `detekt` 已覆盖 |

---

## 通过评估的真正有价值的 IDE SDK 独占能力

### 核心判断原则

> AI Agent 最大的弱点是**幻觉（Hallucination）**。
> 在大型 Android 项目中，AI 读代码时经常会：猜错类型、漏掉引用、误判继承关系、不知道哪些符号在当前作用域可用。
> IDE SDK 的核心价值 = **给 AI 提供确定性的真相（Ground Truth）**，消除猜测。

---

### 能力 1：符号解析 — "这个东西到底是什么？"

**AI 的痛点：** 在大型项目中，AI 看到 `adapter.submitList(items)` 时，经常猜错 `adapter` 的具体类型（特别是涉及泛型、接口多层实现、Kotlin 扩展函数时）。读完一个文件不够，可能需要追溯 5-6 个文件才能确认类型，但 AI 的上下文窗口有限。

**IDE 能给出的确定性答案：** PSI 拥有完整的类型解析引擎（等同于编译器前端），一次调用即返回精确类型，零幻觉。

**SDK API：** `PsiReference.resolve()` → `PsiElement`，`PsiExpression.getType()` → `PsiType`

**MCP Tool：**

```json
{
  "name": "resolve_symbol",
  "description": "精确解析代码中任意位置的符号：返回完全限定类型、声明位置、符号类别",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号"
  },
  "returns": {
    "qualified_type": "com.example.adapter.MyListAdapter<com.example.model.Item>",
    "declaration_file": "src/main/java/com/example/adapter/MyListAdapter.kt",
    "declaration_line": 15,
    "kind": "class | method | field | variable | parameter | property"
  }
}
```

**价值场景举例：**
- AI 要给 `adapter` 添加一个方法调用，但不确定 `adapter` 是 `ListAdapter` 还是 `RecyclerView.Adapter` → 一次调用确认
- Kotlin 代码中大量省略类型声明（`val x = getSomething()`），AI 不知道 `x` 是什么类型 → 一次调用确认
- 泛型嵌套（`LiveData<Resource<List<User>>>`），AI 容易猜错内层类型 → 精确返回

---

### 能力 2：语义查找引用 — "谁在用这个？改了会影响什么？"

**AI 的痛点：** `grep`/`rg` 只能做文本搜索。搜索 `getData` 会匹配到 `getDataList`、`getDataFromCache`、注释中的 `getData`、字符串中的 `"getData"` 等大量误报。而且完全搜不到：通过接口调用的、通过父类引用调用的、Kotlin 属性访问语法（`obj.data` 实际调用 `getData()`）的引用。

**IDE 能给出的确定性答案：** `ReferencesSearch` 基于 PSI 语义模型，只返回真正的代码引用，区分读/写/调用/重写，零误报零漏报。

**SDK API：** `ReferencesSearch.search(PsiElement, SearchScope)` → `Query<PsiReference>`

**MCP Tool：**

```json
{
  "name": "find_usages",
  "description": "语义级查找符号的所有引用（零误报，区分引用类型，含跨文件）",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号",
    "scope": "project | module | file"
  },
  "returns": {
    "total": 23,
    "usages": [
      {
        "file": "src/main/java/com/example/ui/MainFragment.kt",
        "line": 87,
        "code": "viewModel.getData()",
        "usage_type": "call"
      },
      {
        "file": "src/main/java/com/example/ui/DetailFragment.kt",
        "line": 42,
        "code": "override fun getData(): Flow<List<Item>>",
        "usage_type": "override"
      }
    ]
  }
}
```

**价值场景举例：**
- AI 要修改一个方法的签名（加参数/改返回值），需要知道所有调用点 → `find_usages` 精确返回
- AI 要删除一个类/方法，需要确认没有其他地方在用 → `find_usages` 返回空说明安全
- AI 要重命名，需要评估影响范围 → `find_usages` 给出完整列表

---

### 能力 3：作用域查询 — "在这个位置我能用什么？"

**AI 的痛点：** AI 在某个位置写代码时，需要知道**当前作用域内有哪些可用的变量、方法、类**。在复杂的 Android 代码中（Fragment 内部匿名类的 Lambda 里的 suspend 函数中），作用域链极其复杂：局部变量 → 外层 Lambda 捕获 → 匿名类成员 → Fragment 成员 → 父类成员 → 扩展函数 → 导入的顶层函数。AI 经常在这里犯错——使用了不存在的变量，或者漏掉了可以用的成员。

**IDE 能给出的确定性答案：** `PsiScopeProcessor` 沿着 PSI 树向上遍历，收集所有可达的声明，这正是 IDE 代码补全的底层机制。

**SDK API：** `PsiScopeProcessor.execute(PsiElement, ResolveState)` + `PsiElement.processDeclarations()`

**MCP Tool：**

```json
{
  "name": "get_available_symbols",
  "description": "获取指定代码位置的所有可用符号（变量、方法、类、扩展函数等）",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号",
    "filter": "all | variables | methods | types（可选）"
  },
  "returns": {
    "local_variables": [
      { "name": "adapter", "type": "MyListAdapter<Item>" },
      { "name": "binding", "type": "FragmentMainBinding" }
    ],
    "this_members": [
      { "name": "viewModel", "type": "MainViewModel", "kind": "property" },
      { "name": "navigateToDetail", "type": "(Item) -> Unit", "kind": "method" }
    ],
    "extension_functions": [
      { "name": "viewLifecycleOwner.lifecycleScope", "type": "LifecycleCoroutineScope" }
    ],
    "imported_symbols": ["..."]
  }
}
```

**价值场景举例：**
- AI 在 Fragment 的 `onViewCreated` 中写代码，想知道 `viewLifecycleOwner`、`binding`、`viewModel` 是否可用 → 一次查询确认
- AI 在 CoroutineScope 内写代码，不确定外层的变量是否被捕获 → 精确返回
- AI 要调用某个对象的方法，不确定有哪些扩展函数可用 → 完整列出

---

### 能力 4：语义级安全重构 — "帮我改名/移动/提取，别改漏了"

**AI 的痛点：** AI 用文本替换做"重构"时，在 Android 项目中极其容易出问题：
- 重命名一个 Activity，忘记改 `AndroidManifest.xml` 中的注册
- 重命名一个类，忘记改 XML layout 中的 `<com.example.MyView>` 自定义 View 引用
- 移动一个类到新包，漏改了 ProGuard 的 `-keep` 规则
- 提取方法时，错误判断了哪些变量需要作为参数传入

**IDE 的重构引擎**基于 PSI 语义模型，能跨 Java/Kotlin/XML/Manifest/ProGuard **所有文件类型**安全地执行重构，保证零遗漏。

**SDK API：** `RefactoringFactory.createRename()`, `MoveClassesOrPackagesProcessor`, `ExtractMethodProcessor`, `ChangeSignatureProcessor`, `SafeDeleteProcessor`

**MCP Tools：**

```json
{
  "name": "rename_symbol",
  "description": "语义级安全重命名，自动更新所有引用（含 XML/Manifest/ProGuard）",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号",
    "new_name": "新名称"
  },
  "returns": {
    "affected_files": ["被修改的文件路径列表"],
    "changes_count": 17,
    "preview": [
      { "file": "AndroidManifest.xml", "change": ".OldActivity → .NewActivity" },
      { "file": "nav_graph.xml", "change": "app:destination='.OldActivity' → '.NewActivity'" }
    ]
  }
}
```

```json
{
  "name": "move_symbol",
  "description": "安全移动类/方法到新位置，自动更新所有引用和 import",
  "parameters": {
    "file": "文件路径",
    "symbol_name": "要移动的类/方法名",
    "target_package": "目标包名"
  }
}
```

```json
{
  "name": "extract_method",
  "description": "将代码块提取为独立方法，自动分析参数和返回值",
  "parameters": {
    "file": "文件路径",
    "start_line": "起始行",
    "end_line": "结束行",
    "method_name": "新方法名"
  },
  "returns": {
    "inferred_parameters": [{ "name": "item", "type": "Item" }],
    "inferred_return_type": "Boolean",
    "body_preview": "提取后的方法体预览"
  }
}
```

```json
{
  "name": "safe_delete",
  "description": "安全删除：先检查是否有引用，有引用则报告冲突而非直接删除",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号"
  },
  "returns": {
    "safe": true,
    "conflicts": []
  }
}
```

---

### 能力 5：数据流分析 — "这个变量在这里可能是 null 吗？"

**AI 的痛点：** Kotlin 的空安全在编译器层面解决了很多问题，但 Java 代码、Java-Kotlin 互调用、平台类型（`!`）等场景下，AI 很难判断某个变量在某个代码点是否可能为 null。更广泛地说，AI 无法可靠地追踪一个值经过多次赋值、条件分支、函数传递后的可能取值范围。

**IDE 的数据流分析引擎（DFA）** 通过抽象解释追踪每个变量在每个代码点的可能状态（null/非null、值范围、类型收窄等），这是编译器级别的分析，AI 读代码无法复现。

**SDK API：** `DfaUtil`, `DataFlowInspectionBase`, `SliceUtil`（前向/后向切片分析）

**MCP Tools：**

```json
{
  "name": "analyze_nullability",
  "description": "分析指定变量在指定代码点是否可能为 null（基于数据流分析，非猜测）",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "variable_name": "变量名"
  },
  "returns": {
    "nullability": "definitely_not_null | possibly_null | definitely_null",
    "reason": "Assigned non-null at line 23, no reassignment on path to line 45",
    "null_paths": ["如果 possibly_null，列出可能为 null 的代码路径"]
  }
}
```

```json
{
  "name": "trace_data_flow",
  "description": "追踪数据的来源（backward）或去向（forward），揭示值的传播路径",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号",
    "direction": "backward | forward"
  },
  "returns": {
    "flow_paths": [
      {
        "path": [
          { "file": "Repository.kt", "line": 30, "code": "val data = api.fetchData()" },
          { "file": "ViewModel.kt", "line": 52, "code": "_state.value = data" },
          { "file": "Fragment.kt", "line": 78, "code": "val items = state.value" }
        ]
      }
    ]
  }
}
```

**价值场景举例：**
- Java 方法返回 `@Nullable String`，经过 3 层传递后在 Kotlin 代码中使用，AI 判断不了是否需要 `?.` → DFA 给出确定答案
- `if (x != null)` 分支内调用了一个可能重新赋 null 的函数，AI 以为安全但实际不安全 → DFA 精确追踪
- 一个值从网络层传到 UI 层经过了 5 个函数，AI 想知道"这个值从哪来的" → 后向切片一次调用返回完整路径

---

### 能力 6：继承/调用层级分析 — "这个接口有哪些实现？这个方法的调用链是什么？"

**AI 的痛点：** 面向接口编程是 Android 架构的基础（Repository 接口、UseCase 接口、DAO 接口等）。AI 看到 `repository.getUsers()` 时，需要知道 `Repository` 接口有哪些实现类、当前通过 DI 注入的是哪个实现。`grep "class.*Repository"` 会漏掉通过泛型、匿名类、内部类实现的情况。调用层级同理——追踪"谁调用了谁"需要穿越接口边界。

**IDE 通过 PSI** 维护了完整的继承关系图和方法调用图，能精确返回所有实现类/子类，以及穿越接口边界的调用链。

**SDK API：** `ClassInheritorsSearch.search(PsiClass)`, `MethodReferencesSearch.search(PsiMethod)`, `SuperMethodsSearch.search(PsiMethod)`

**MCP Tools：**

```json
{
  "name": "get_type_hierarchy",
  "description": "获取类/接口的完整继承层级树（向上：父类链；向下：所有子类/实现类）",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号"
  },
  "returns": {
    "target": "com.example.data.UserRepository",
    "supers": [
      "com.example.domain.Repository<User>",
      "java.lang.Object"
    ],
    "inheritors": [
      { "class": "com.example.data.UserRepositoryImpl", "file": "UserRepositoryImpl.kt" },
      { "class": "com.example.data.FakeUserRepository", "file": "FakeUserRepository.kt" }
    ]
  }
}
```

```json
{
  "name": "get_call_hierarchy",
  "description": "获取方法的调用链（callers：谁调用了它；callees：它调用了谁），可穿越接口边界",
  "parameters": {
    "file": "文件路径",
    "line": "行号",
    "column": "列号",
    "direction": "callers | callees",
    "depth": "分析深度（默认3层）"
  },
  "returns": {
    "target": "UserRepository.getUsers()",
    "callers": [
      {
        "method": "GetUsersUseCase.invoke()",
        "file": "GetUsersUseCase.kt",
        "line": 15,
        "callers": [
          {
            "method": "UserViewModel.loadUsers()",
            "file": "UserViewModel.kt",
            "line": 32
          }
        ]
      }
    ]
  }
}
```

**价值场景举例：**
- AI 要修改一个 interface 的方法签名，需要知道有哪些实现类要同步修改 → `get_type_hierarchy` 返回所有实现类
- AI 修改了一个底层工具方法，想知道影响范围 → `get_call_hierarchy(callers, depth=5)` 展示完整的影响传播链
- AI 发现一个 bug，想追踪数据是从哪里被错误地传入的 → `get_call_hierarchy(callers)` + `trace_data_flow(backward)` 组合使用

---

### 能力 7：结构化搜索 — "找出所有符合某个代码模式的地方"

**AI 的痛点：** 正则表达式无法可靠地匹配代码模式。例如搜索"所有在主线程调用了挂起函数的地方"、"所有没有取消的 CoroutineScope"、"所有直接 new 了 Retrofit 而不是用 DI 的地方"——这些模式涉及语法结构、类型信息、作用域关系，`grep`/`rg` 完全无能为力。

**IntelliJ 的 Structural Search & Replace（SSR）** 是基于 AST 的模式匹配引擎，支持类型约束、变量绑定、正则过滤，能精确匹配复杂的代码模式。

**SDK API：** `StructuralSearchProfile`, `MatchOptions`, `Matcher`（参考 [IntelliJ SSR 文档](https://www.jetbrains.com/help/idea/structural-search-and-replace.html)）

**MCP Tool：**

```json
{
  "name": "structural_search",
  "description": "基于代码结构（AST）的模式搜索，非文本匹配",
  "parameters": {
    "pattern": "搜索模板（SSR 语法，使用 $变量$ 作为占位符）",
    "file_type": "kotlin | java | xml",
    "scope": "project | module | directory",
    "filters": {
      "type_constraint": "对变量的类型约束（可选）",
      "count_constraint": "匹配数量约束（可选）"
    }
  },
  "returns": [
    { "file": "匹配文件", "line": "行号", "matched_code": "匹配的代码片段" }
  ]
}
```

**实际搜索模板示例：**

| 目标 | SSR 模板 | grep 能做到吗？ |
|------|---------|---------------|
| 所有在 `runOnUiThread` 里调用网络请求的代码 | `runOnUiThread { $body$ }` + `$body$` 类型约束含 Retrofit/OkHttp | 不能 |
| 所有未关闭的 `Cursor`（数据库） | `val $c$ = $expr$.query($args$)` + 分析 `$c$` 是否在作用域结束前调用了 `.close()` | 不能 |
| 所有把 `Context`（Activity）传给了长生命周期对象的代码 | `$singleton$.$method$(this)` + `$singleton$` 为单例类型 | 不能 |
| 所有直接使用 `Thread()` 而不是用协程/线程池的代码 | `Thread($arg$).start()` | `grep "Thread"` 误报太多 |
| 所有实现了 RecyclerView.Adapter 但没有 DiffUtil 的类 | `class $C$ : RecyclerView.Adapter<$VH$>()` + 检查 $C$ 内无 `DiffUtil` 引用 | 不能 |

---

### 能力 8：Java → Kotlin 转换

**AI 的痛点：** AI 手动将 Java 转为 Kotlin 时，经常在以下方面出错：
- `@Nullable`/`@NonNull` 注解转换为 Kotlin 空安全类型
- SAM 转换（Java 单方法接口 → Kotlin Lambda）
- Java getter/setter → Kotlin property
- Java static → Kotlin companion object / top-level function
- Java Builder pattern → Kotlin DSL / default parameters
- 保留原有功能的同时让代码"Kotlin 化"

**IntelliJ 的 J2K 转换器**经过多年打磨，是公认的最佳 Java→Kotlin 转换工具，处理了数百种边缘情况。AI 自己做转换的质量远不如 J2K。

**SDK API：** `org.jetbrains.kotlin.j2k.J2kConverterExtension` (Kotlin Plugin 内部 API)

**MCP Tool：**

```json
{
  "name": "convert_java_to_kotlin",
  "description": "使用 IntelliJ J2K 引擎将 Java 文件/代码片段转换为惯用 Kotlin",
  "parameters": {
    "file": "Java 文件路径（转换整个文件）",
    "or_code_snippet": "Java 代码片段（转换片段）"
  },
  "returns": {
    "kotlin_code": "转换后的 Kotlin 代码",
    "warnings": ["转换过程中的警告（如无法推断空安全的地方）"]
  }
}
```

---

### 能力 9：Inspection QuickFix 执行 — "不只是发现问题，还能自动修复"

**AI 的痛点：** 这里的价值不在于"检测"（`./gradlew lint` 可以检测），而在于**修复的精确性**。IntelliJ 内置 1000+ 条 Inspection 规则，每条规则都有对应的 `LocalQuickFix` 实现，这些 Fix 基于 PSI 模型修改代码，保证了语法和语义的正确性。AI 自己修复问题时可能引入新问题，而 QuickFix 是经过大量测试的确定性修复。

**关键场景：批量迁移/现代化。** 不是修一两个问题，而是在大型项目中批量执行某类修复（如"把所有 `findViewById` 替换为 ViewBinding"、"把所有弃用 API 替换为新 API"）。AI 一个个手动改容易出错，QuickFix 批量执行不会。

**SDK API：** `InspectionEngine.runInspectionOnFile()` → `ProblemDescriptor.getFixes()` → `LocalQuickFix.applyFix()`

**MCP Tool：**

```json
{
  "name": "run_and_fix",
  "description": "对文件/模块运行指定检查，并批量执行自动修复",
  "parameters": {
    "scope": "file | module | project",
    "path": "文件或模块路径",
    "inspection_ids": ["要运行的检查规则 ID（为空则运行全部）"],
    "auto_fix": true,
    "dry_run": "预览模式，仅显示将要修复的内容（默认 false）"
  },
  "returns": {
    "problems_found": 47,
    "problems_fixed": 42,
    "unfixable": [
      { "file": "...", "line": "...", "reason": "需要人工判断" }
    ]
  }
}
```

**最有价值的批量修复场景：**
- `ReplaceWith` 弃用 API 自动替换（Kotlin 的 `@Deprecated(replaceWith = ...)` 对应的自动替换）
- Java 匿名类 → Lambda 批量转换
- `!!` 操作符 → 安全的 null 处理
- `var` → `val`（不可变化检测）
- 字符串硬编码 → `strings.xml` 资源提取
- `runOnUiThread` → `lifecycleScope.launch(Dispatchers.Main)`

---

### 能力 10：Build Variant 感知的代码模型

**AI 的痛点：** Android 项目的 Build Variant 导致**同一个类名可能对应不同的源文件**。`src/debug/java/Config.kt` 和 `src/release/java/Config.kt` 是不同的文件，当前 IDE 选中的 Variant 决定了哪个生效。AI 只看文件系统，不知道当前激活的是哪个 Variant，也不知道 `Config` 到底解析到了哪个文件。

**IDE 维护着"当前选中 Variant"的状态**，PSI 的符号解析范围由此决定。这是 CLI 完全无法获取的信息（Gradle 可以列出 Variant 列表，但不知道 IDE 当前选中了哪个）。

**SDK API：** `AndroidModuleModel.getSelectedVariant()`, `IdeVariant.getMainArtifact().getSourceProvider()`

**MCP Tool：**

```json
{
  "name": "get_active_variant_context",
  "description": "获取当前 Build Variant 下的有效源集和符号解析上下文",
  "parameters": {
    "module": "模块名（默认 app）"
  },
  "returns": {
    "variant": "freeDebug",
    "build_type": "debug",
    "flavors": ["free"],
    "active_source_dirs": [
      "src/main/java",
      "src/free/java",
      "src/debug/java",
      "src/freeDebug/java"
    ],
    "inactive_source_dirs": [
      "src/release/java",
      "src/paid/java"
    ],
    "resolved_manifest": "合并后的 Manifest 内容摘要",
    "build_config_fields": {
      "DEBUG": "true",
      "FLAVOR": "free",
      "APPLICATION_ID": "com.example.app.free.debug"
    }
  }
}
```

---

---

## 项目级 PSI 分析：从"看符号"到"看架构"

> 前面 10 项 PSI 能力都是**符号级**的——一次处理一个类型、一个引用、一个方法。
> 但 PSI 的真正威力在于：当你**遍历整个项目的语法树**，构建出完整的代码关系图谱后，
> AI 获得的不再是"这个变量是什么类型"，而是**"这个项目的架构是怎样的、哪里有问题、怎么改最安全"**。

### 关键技术基础

| 技术 | 说明 |
|------|------|
| **UAST** | Unified Abstract Syntax Tree，统一的抽象语法树。Java 和 Kotlin 的 PSI 不同，但 UAST 提供统一接口遍历两者，无需分别处理 |
| **StubIndex** | 不加载完整 AST，仅从序列化的 Stub 中查询类名、方法名、注解等，O(1) 级别查找，支撑大项目分析 |
| **AnnotatedElementsSearch** | 一次调用找到项目中所有带某个注解的类/方法/字段 |
| **GlobalInspectionTool** | 支持批量模式的检查框架，可访问完整的类间引用关系图 |
| **DependenciesBuilder** | 包/模块级别的依赖关系构建器 |
| **DuplicatesProfile** | 基于 AST 结构的代码克隆检测引擎 |

---

### 能力 P1：项目架构全景图 — "这个项目长什么样？"

**AI 的痛点：** AI 接手一个陌生项目时，最大的困难不是读懂单个文件，而是**不知道整体结构**——有多少模块、模块间怎么依赖、代码按什么架构分层、入口在哪里。AI 通常靠 `ls` 和 `tree` 猜测，经常猜错。

**PSI 全项目遍历能做到：** 通过 `AllClassesSearch` + `ModuleManager` + `UAST` 遍历整个项目，构建完整的项目结构图谱。

**MCP Tool：**

```json
{
  "name": "get_project_architecture",
  "description": "生成项目的完整架构全景图（模块、层级、类的分布、依赖关系）",
  "parameters": {
    "detail_level": "overview | module | package | class"
  },
  "returns": {
    "modules": [
      {
        "name": "app",
        "type": "android-application",
        "depends_on": ["domain", "data", "core-ui"],
        "stats": { "classes": 87, "kotlin_files": 62, "java_files": 25 },
        "packages": [
          {
            "name": "com.example.app.ui",
            "classes": 23,
            "role": "presentation-layer",
            "key_classes": ["MainActivity", "MainFragment", "MainViewModel"]
          }
        ]
      },
      {
        "name": "domain",
        "type": "kotlin-library",
        "depends_on": [],
        "stats": { "classes": 34, "interfaces": 12 },
        "packages": [
          {
            "name": "com.example.domain.usecase",
            "classes": 8,
            "role": "business-logic",
            "key_classes": ["GetUsersUseCase", "LoginUseCase"]
          }
        ]
      }
    ],
    "architecture_pattern": "Clean Architecture (domain/data/presentation separation detected)",
    "entry_points": ["MainActivity", "MyApplication"],
    "dependency_direction": "app → domain ← data (domain has zero outward dependencies)"
  }
}
```

---

### 能力 P2：类间依赖关系图 — "改了这个类会波及到哪里？"

**AI 的痛点：** AI 修改一个类时，不知道这个修改会"传染"多远。`find_usages` 只找直接引用者，但真实影响是多跳传递的：改了 A → B 依赖 A → C 依赖 B → 整个 feature 崩了。

**PSI 全项目遍历能做到：** 构建类级别的有向依赖图（Class Dependency Graph），从中提取：
- 任意两个类之间的依赖路径
- 循环依赖检测
- 变更传播范围（N 跳影响分析）
- 模块间的耦合度量化

**MCP Tools：**

```json
{
  "name": "get_dependency_graph",
  "description": "构建类/包级别的依赖关系图",
  "parameters": {
    "scope": "module | package | class",
    "target": "模块名或包名（可选，为空则分析整个项目）",
    "depth": "分析深度（默认全部）"
  },
  "returns": {
    "nodes": [
      { "name": "UserRepository", "package": "com.example.data", "type": "class" }
    ],
    "edges": [
      { "from": "UserViewModel", "to": "GetUsersUseCase", "type": "field_dependency" },
      { "from": "GetUsersUseCase", "to": "UserRepository", "type": "constructor_injection" }
    ],
    "cycles": [
      { "path": ["ServiceA", "ServiceB", "ServiceA"], "severity": "critical" }
    ],
    "coupling_metrics": {
      "UserRepository": { "afferent": 5, "efferent": 3, "instability": 0.375 }
    }
  }
}
```

```json
{
  "name": "analyze_change_impact",
  "description": "分析修改某个类/方法后的影响传播范围（多跳分析）",
  "parameters": {
    "file": "文件路径",
    "class_name": "类名",
    "change_type": "signature_change | behavior_change | delete",
    "max_hops": "最大传播跳数（默认 3）"
  },
  "returns": {
    "direct_impact": ["直接依赖的类列表"],
    "transitive_impact": {
      "hop_1": ["FirstLevelClass1", "FirstLevelClass2"],
      "hop_2": ["SecondLevelClass1"],
      "hop_3": []
    },
    "affected_modules": ["app", "feature-login"],
    "affected_tests": ["UserRepositoryTest", "LoginViewModelTest"],
    "risk_level": "medium",
    "suggestion": "Consider adding an interface to decouple UserRepository from its consumers"
  }
}
```

---

### 能力 P3：注解驱动的框架视图 — "项目里所有的 Entity/API/DI 组件在哪？"

**AI 的痛点：** Android 项目大量使用注解驱动的框架（Room、Retrofit、Hilt、Compose、Navigation 等）。AI 想了解"数据库有哪些表"、"有哪些 API 接口"、"DI 图长什么样"时，需要在几百个文件中搜索对应注解，然后人工拼凑出全貌。

**PSI 能做到：** `AnnotatedElementsSearch.searchPsiClasses(annotationClass, projectScope)` 一次调用找到项目中所有带特定注解的元素，再通过 PSI 提取注解参数值、类结构等信息，构建框架级别的全景图。

**MCP Tools：**

```json
{
  "name": "get_framework_view",
  "description": "基于注解扫描，生成特定框架的项目全景视图",
  "parameters": {
    "framework": "room | retrofit | hilt | compose | navigation"
  }
}
```

**各框架返回示例：**

**Room（数据库视图）：**
```json
{
  "framework": "room",
  "database": {
    "class": "AppDatabase",
    "version": 5,
    "entities": [
      {
        "class": "UserEntity",
        "table_name": "users",
        "fields": [
          { "name": "id", "type": "Long", "primary_key": true },
          { "name": "name", "type": "String", "nullable": false },
          { "name": "email", "type": "String", "nullable": true, "index": true }
        ],
        "relations": [
          { "type": "one-to-many", "target": "PostEntity", "via": "userId" }
        ]
      }
    ],
    "daos": [
      {
        "class": "UserDao",
        "queries": [
          { "method": "getAll", "sql": "SELECT * FROM users", "return": "Flow<List<UserEntity>>" },
          { "method": "findById", "sql": "SELECT * FROM users WHERE id = :id", "return": "UserEntity?" }
        ]
      }
    ],
    "migrations": ["Migration_3_4", "Migration_4_5"]
  }
}
```

**Retrofit（API 视图）：**
```json
{
  "framework": "retrofit",
  "services": [
    {
      "interface": "UserApiService",
      "base_url_field": "BASE_URL in NetworkModule",
      "endpoints": [
        { "method": "GET", "path": "/users", "return": "List<UserDto>", "function": "getUsers()" },
        { "method": "POST", "path": "/users", "body": "CreateUserRequest", "return": "UserDto", "function": "createUser(body)" },
        { "method": "GET", "path": "/users/{id}", "return": "UserDto", "function": "getUserById(id: Long)" }
      ]
    }
  ],
  "interceptors": ["AuthInterceptor", "LoggingInterceptor"],
  "total_endpoints": 15
}
```

**Hilt（DI 视图）：**
```json
{
  "framework": "hilt",
  "components": {
    "SingletonComponent": {
      "modules": ["NetworkModule", "DatabaseModule", "RepositoryModule"],
      "provides": [
        { "type": "Retrofit", "provider": "NetworkModule.provideRetrofit()", "scope": "Singleton" },
        { "type": "AppDatabase", "provider": "DatabaseModule.provideDatabase()", "scope": "Singleton" },
        { "type": "UserRepository", "provider": "RepositoryModule.bindUserRepository()", "binds_to": "UserRepositoryImpl" }
      ]
    },
    "ViewModelComponent": {
      "injected_viewmodels": ["MainViewModel", "LoginViewModel", "ProfileViewModel"]
    }
  },
  "entry_points": [
    { "class": "MainActivity", "annotation": "@AndroidEntryPoint" },
    { "class": "MyApplication", "annotation": "@HiltAndroidApp" }
  ],
  "missing_bindings": [],
  "scope_violations": []
}
```

**Compose（UI 组件视图）：**
```json
{
  "framework": "compose",
  "screens": [
    {
      "function": "HomeScreen",
      "file": "HomeScreen.kt",
      "parameters": ["viewModel: HomeViewModel", "onNavigateToDetail: (String) -> Unit"],
      "calls_composables": ["UserList", "TopAppBar", "FloatingActionButton"],
      "preview_configs": [
        { "name": "Light", "uiMode": "normal" },
        { "name": "Dark", "uiMode": "night" }
      ]
    }
  ],
  "reusable_components": ["UserCard", "LoadingIndicator", "ErrorView"],
  "theme": {
    "file": "Theme.kt",
    "color_scheme": "dynamic (Material You)",
    "typography_defined": true
  }
}
```

---

### 能力 P4：架构规则校验 — "代码有没有违反架构约定？"

**AI 的痛点：** 团队通常有架构约定（如"domain 层不能依赖 Android framework"、"ViewModel 不能直接访问 Repository 实现类"），但 AI 不知道这些约定。即使告诉了 AI，AI 也无法可靠地验证整个项目是否遵守。

**PSI 全项目分析能做到：** 遍历所有类的 import 和引用关系，按包/模块归类，然后检查是否存在违反预定义规则的依赖关系。

**MCP Tool：**

```json
{
  "name": "check_architecture_rules",
  "description": "验证项目代码是否遵守架构规则（检测层级违规、非法依赖等）",
  "parameters": {
    "rules": [
      {
        "name": "domain-independence",
        "description": "domain 模块不能依赖 Android 框架",
        "source": "com.example.domain.**",
        "must_not_depend_on": ["android.**", "androidx.**"]
      },
      {
        "name": "presentation-uses-usecase",
        "description": "ViewModel 只能通过 UseCase 访问数据，不能直接依赖 Repository",
        "source": "**.*ViewModel",
        "must_not_depend_on": ["**.*Repository", "**.*RepositoryImpl"]
      },
      {
        "name": "no-circular-modules",
        "description": "模块间不能有循环依赖"
      }
    ]
  },
  "returns": {
    "passed": 1,
    "failed": 2,
    "violations": [
      {
        "rule": "domain-independence",
        "violator": "com.example.domain.usecase.SyncUseCase",
        "illegal_dependency": "android.content.Context",
        "file": "SyncUseCase.kt",
        "line": 5,
        "import": "import android.content.Context",
        "suggestion": "Inject a platform-agnostic interface instead of android.content.Context"
      },
      {
        "rule": "presentation-uses-usecase",
        "violator": "com.example.app.ui.ProfileViewModel",
        "illegal_dependency": "com.example.data.UserRepositoryImpl",
        "file": "ProfileViewModel.kt",
        "line": 12,
        "suggestion": "Depend on UserRepository interface via GetUserProfileUseCase"
      }
    ]
  }
}
```

---

### 能力 P5：死代码检测 — "这些代码还有用吗？"

**AI 的痛点：** 项目演化过程中积累了大量死代码——没有被任何地方调用的 public 方法、没有被引用的类、参数列表中未使用的参数等。`grep` 找不到死代码，因为它无法区分"文本上没出现"和"语义上没被引用"（如通过反射调用的、被注解处理器使用的）。

**PSI 全项目分析能做到：** 构建完整的调用图（Call Graph），标记每个公共符号的引用计数。引用计数为 0 且不在特定白名单中（如 `@JvmStatic`、Activity 子类等）的即为死代码。

**MCP Tool：**

```json
{
  "name": "find_dead_code",
  "description": "检测项目中未被引用的类、方法、字段（排除框架入口点）",
  "parameters": {
    "scope": "project | module",
    "target": "模块名（可选）",
    "exclude_patterns": ["测试类、框架入口点等排除规则"],
    "include_types": ["class", "method", "field", "parameter"]
  },
  "returns": {
    "dead_classes": [
      { "class": "OldUserMapper", "file": "OldUserMapper.kt", "last_modified": "2023-06-15", "lines": 87 }
    ],
    "dead_methods": [
      { "method": "UserRepository.legacySync()", "file": "UserRepository.kt", "line": 145, "visibility": "public" }
    ],
    "dead_fields": [
      { "field": "Constants.OLD_BASE_URL", "file": "Constants.kt", "line": 23 }
    ],
    "dead_parameters": [
      { "parameter": "includeArchived in UserDao.getUsers()", "file": "UserDao.kt", "line": 12 }
    ],
    "total_dead_lines": 342,
    "cleanup_impact": "可安全删除 342 行代码，涉及 15 个文件"
  }
}
```

---

### 能力 P6：代码克隆检测 — "哪些代码是复制粘贴的？"

**AI 的痛点：** 项目中存在大量"复制粘贴编程"——开发者复制了一段代码然后略作修改。`diff` 和 `grep` 找不到这些克隆，因为变量名、类名已经不同了。但这些克隆代码维护成本高（改一处忘改另一处就是 bug）。

**PSI 的 AST 级别克隆检测** 不看文本，而是比较**语法树结构**。即使所有变量名都不同，只要结构相似就能检测出来。

**SDK API：** `DuplicatesProfile`, `DuplicatesInspectionBase`, `DuplocatorState`

**MCP Tool：**

```json
{
  "name": "find_code_clones",
  "description": "基于 AST 结构检测代码克隆（即使变量名不同也能发现）",
  "parameters": {
    "scope": "project | module | file",
    "target": "分析范围（可选）",
    "min_size": "最小克隆大小（语句数，默认 5）",
    "ignore_names": "是否忽略标识符名称差异（默认 true）"
  },
  "returns": {
    "clone_groups": [
      {
        "pattern_description": "数据库查询 + 错误处理 + 结果映射（结构相同，变量名不同）",
        "instances": [
          { "file": "UserRepository.kt", "start_line": 45, "end_line": 62, "snippet_preview": "..." },
          { "file": "PostRepository.kt", "start_line": 38, "end_line": 55, "snippet_preview": "..." },
          { "file": "CommentRepository.kt", "start_line": 51, "end_line": 68, "snippet_preview": "..." }
        ],
        "suggestion": "Extract common pattern to a generic repository base method"
      }
    ],
    "total_cloned_lines": 156,
    "clone_percentage": "8.3% of project code"
  }
}
```

---

### 能力 P7：代码复杂度与健康度指标 — "哪些代码最危险？"

**AI 的痛点：** AI 在大项目中不知道该优先关注哪些代码。有些文件"看起来很长"但逻辑简单，有些文件"看起来不长"但逻辑极其复杂（深层嵌套、大量分支、高耦合）。AI 缺乏量化的方法来评估代码质量。

**PSI 能做到：** 遍历每个方法/类的 AST，计算精确的复杂度指标。这些指标基于语法结构（分支数、嵌套深度、参数数量、依赖数量等），CLI 工具（如 `detekt`）虽然也能计算部分指标，但 PSI 提供的是**跨类型解析的**指标——比如方法的真实扇入（谁调用了它）、扇出（它调用了谁），这需要全项目引用分析。

**MCP Tool：**

```json
{
  "name": "get_code_health",
  "description": "计算代码健康度指标，识别项目中最危险/最复杂的代码",
  "parameters": {
    "scope": "project | module",
    "target": "分析范围（可选）",
    "metrics": ["complexity", "coupling", "cohesion", "size"]
  },
  "returns": {
    "hotspots": [
      {
        "class": "PaymentProcessor",
        "file": "PaymentProcessor.kt",
        "issues": {
          "cyclomatic_complexity": { "value": 47, "threshold": 15, "verdict": "critical" },
          "method_count": { "value": 32, "threshold": 20, "verdict": "warning" },
          "max_nesting_depth": { "value": 7, "threshold": 4, "verdict": "critical" },
          "afferent_coupling": { "value": 15, "description": "15 classes depend on this" },
          "efferent_coupling": { "value": 23, "description": "this depends on 23 classes" }
        },
        "risk_score": 92,
        "suggestion": "God class anti-pattern. Split into PaymentValidator, PaymentExecutor, PaymentLogger"
      }
    ],
    "module_health": {
      "app": { "avg_complexity": 8.2, "coupling_score": "medium", "cohesion_score": "low" },
      "domain": { "avg_complexity": 3.1, "coupling_score": "low", "cohesion_score": "high" }
    },
    "overall_score": 67
  }
}
```

---

### 能力 P8：设计模式检测与反模式识别 — "代码用了什么模式？哪里有坏味道？"

**AI 的痛点：** AI 读代码时能识别简单的模式（如明显的 Singleton），但对于复杂的模式（如通过 DI 实现的 Strategy、通过接口 + sealed class 实现的 State Machine）识别能力有限。反模式（God Class、Feature Envy、Inappropriate Intimacy）更是需要量化指标才能可靠判断。

**PSI 全项目分析能做到：** 通过分析类结构（字段、方法、继承关系、注解）+ 依赖关系，自动识别设计模式和反模式。

**MCP Tool：**

```json
{
  "name": "detect_patterns",
  "description": "检测项目中使用的设计模式和存在的反模式",
  "parameters": {
    "scope": "project | module",
    "detect": ["patterns", "anti_patterns", "both"]
  },
  "returns": {
    "patterns_found": [
      {
        "pattern": "Repository Pattern",
        "instances": [
          { "interface": "UserRepository", "impl": "UserRepositoryImpl", "module": "data" }
        ]
      },
      {
        "pattern": "Observer (Flow-based)",
        "instances": [
          { "producer": "UserRepository.getUsers(): Flow<List<User>>",
            "consumers": ["UserViewModel", "SyncWorker"] }
        ]
      },
      {
        "pattern": "Factory Method",
        "instances": [
          { "factory": "ViewModelFactory", "creates": ["MainViewModel", "LoginViewModel"] }
        ]
      }
    ],
    "anti_patterns_found": [
      {
        "anti_pattern": "God Class",
        "class": "AppManager",
        "evidence": "42 methods, 15 fields, 8 different responsibilities",
        "suggestion": "Split into: SessionManager, ConfigManager, AnalyticsManager, ..."
      },
      {
        "anti_pattern": "Feature Envy",
        "method": "OrderProcessor.calculateShipping()",
        "evidence": "Accesses 7 fields of Address class but only 1 of its own class",
        "suggestion": "Move calculateShipping() to Address or create ShippingCalculator"
      },
      {
        "anti_pattern": "Circular Dependency",
        "cycle": ["AuthManager", "UserManager", "SessionManager", "AuthManager"],
        "suggestion": "Extract shared interface or introduce mediator"
      }
    ]
  }
}
```

---

### 能力 P9：接口契约与 API 表面分析 — "模块的边界清晰吗？"

**AI 的痛点：** 在多模块项目中，AI 要给某个模块添加功能时，不知道这个模块对外暴露了什么 API、内部实现了什么。容易不小心把内部实现类暴露出去，或者在错误的模块添加代码。

**PSI 全项目分析能做到：** 扫描每个模块的所有公共类/方法，构建 API 表面清单。结合 Kotlin 的 `internal` 可见性和 Java 的包可见性，精确区分"对外 API"和"内部实现"。

**MCP Tool：**

```json
{
  "name": "get_module_api_surface",
  "description": "分析模块的 API 表面——对外暴露了什么，内部实现了什么",
  "parameters": {
    "module": "模块名"
  },
  "returns": {
    "public_api": {
      "classes": [
        {
          "name": "UserRepository",
          "kind": "interface",
          "methods": [
            { "name": "getUsers", "signature": "(): Flow<List<User>>", "visibility": "public" },
            { "name": "getUserById", "signature": "(id: Long): User?", "visibility": "public" }
          ]
        }
      ],
      "total_public_symbols": 23
    },
    "internal_impl": {
      "classes": ["UserRepositoryImpl", "UserApiMapper", "UserCacheManager"],
      "total_internal_symbols": 67
    },
    "api_stability_score": "high (interface-based, few concrete type exposures)",
    "leaky_abstractions": [
      {
        "issue": "UserRepositoryImpl is public but should be internal",
        "file": "UserRepositoryImpl.kt",
        "suggestion": "Add 'internal' modifier — consumers should only depend on UserRepository interface"
      }
    ]
  }
}
```

---

### 能力 P10：项目级错误处理分析 — "异常在哪里被吞掉了？"

**AI 的痛点：** Android 项目中最难调试的 bug 之一是"异常被静默吞掉"——某个 `try-catch` 捕获了异常但只是 `e.printStackTrace()` 或完全为空。AI 在读单个文件时很难发现这类问题，因为它需要追踪异常从抛出到处理的完整路径。

**PSI 全项目分析能做到：** 遍历所有 `try-catch` 块、`runCatching`、`catch` 表达式，分析：
- 哪些异常被捕获后没有正确处理
- 异常从抛出点到捕获点的传播路径是否合理
- 是否存在过于宽泛的 `catch (Exception e)` 捕获

**MCP Tool：**

```json
{
  "name": "analyze_error_handling",
  "description": "分析项目的异常处理质量——找出被吞掉的异常、过宽的捕获、缺失的处理",
  "parameters": {
    "scope": "project | module"
  },
  "returns": {
    "swallowed_exceptions": [
      {
        "file": "NetworkRepository.kt",
        "line": 89,
        "code": "catch (e: Exception) { /* empty */ }",
        "context": "Inside fetchUserData(), network errors will be silently lost",
        "severity": "critical"
      }
    ],
    "overly_broad_catches": [
      {
        "file": "PaymentProcessor.kt",
        "line": 156,
        "code": "catch (e: Exception) { showError(\"Something went wrong\") }",
        "caught_types_in_try": ["IOException", "JsonParseException", "IllegalStateException"],
        "suggestion": "Handle IOException (network) and JsonParseException (parsing) separately"
      }
    ],
    "uncaught_paths": [
      {
        "exception": "SQLiteException",
        "thrown_at": "UserDao.insert()",
        "propagation": "UserDao → UserRepository → UserViewModel.save()",
        "no_catch_at": "UserViewModel.save() — will crash the app"
      }
    ]
  }
}
```

---

### 项目级 PSI 能力总结

| 层级 | 之前的能力（符号级） | 新增能力（项目级） |
|------|--------------------|--------------------|
| **单个符号** | 类型解析、查找引用、作用域查询 | — |
| **单个文件** | 数据流分析、QuickFix | — |
| **类与类之间** | 继承/调用层级 | **P2 依赖关系图 + 变更影响分析** |
| **包与包之间** | 结构化搜索 | **P4 架构规则校验** |
| **模块级别** | Variant 感知 | **P9 API 表面分析** |
| **整个项目** | — | **P1 架构全景图** |
| **框架理解** | — | **P3 注解驱动的框架视图** |
| **质量评估** | — | **P5 死代码、P6 克隆、P7 健康度、P8 模式/反模式** |
| **安全性** | — | **P10 异常处理分析** |

---

## 非 PSI 能力：IDE 独占的其他子系统

以下能力**完全不依赖 PSI 语法树**，来自 IntelliJ/Android Studio 的其他独立子系统。

---

### 能力 11：Local History — AI 操作的安全网和时光机

**本质：** IntelliJ 维护了一套**独立于 Git 的、自动的、细粒度的文件版本历史**。每一次文件保存都会被记录，即使从未 `git commit`，也能回溯到任意时间点的文件内容。

**为什么 CLI 做不到：** `git` 只记录显式 commit 的快照。如果 AI Agent 连续做了 20 次文件修改但没有 commit，中间某次改坏了，`git` 无法回到"第 12 次修改之后"的状态。而 Local History 记录了每一次保存。

**为什么对 AI Agent 极其有价值：**
- AI 做大规模修改时经常"改着改着就坏了"，需要一个**自动的细粒度回滚机制**
- 可以在 AI 开始操作前自动打标签（`putSystemLabel`），操作失败后一键回到标签
- 不需要 AI 记住自己做了什么修改——Local History 全部记录了

**SDK API：** `LocalHistory.getInstance()`, `putSystemLabel(Project, String)`, `getByteContent(VirtualFile, FileRevisionTimestampComparator)`

**MCP Tools：**

```json
{
  "name": "create_checkpoint",
  "description": "在 Local History 中创建命名检查点（在 AI 大规模操作前调用）",
  "parameters": {
    "label": "检查点名称，如 'before-refactoring'"
  }
}
```

```json
{
  "name": "get_file_history",
  "description": "获取文件的本地修改历史（含每次保存的时间戳和内容摘要）",
  "parameters": {
    "file": "文件路径",
    "since": "起始时间（可选，如 '2h ago', 'today'）"
  },
  "returns": [
    { "timestamp": "2024-01-15T10:23:45", "label": "before-refactoring", "size_delta": "+15 lines" },
    { "timestamp": "2024-01-15T10:25:12", "label": null, "size_delta": "-3 lines" }
  ]
}
```

```json
{
  "name": "rollback_to_checkpoint",
  "description": "将文件回滚到指定检查点或时间戳的状态",
  "parameters": {
    "file": "文件路径（可选，为空则回滚整个项目）",
    "label": "检查点名称",
    "or_timestamp": "或指定时间戳"
  }
}
```

```json
{
  "name": "diff_with_checkpoint",
  "description": "对比文件当前内容与某个检查点时的内容",
  "parameters": {
    "file": "文件路径",
    "label": "检查点名称"
  },
  "returns": {
    "diff": "unified diff 格式的差异"
  }
}
```

**典型使用场景：**
- AI 开始大规模重构前 → `create_checkpoint("before-refactoring")`
- 重构后编译失败 → `rollback_to_checkpoint("before-refactoring")` → 重新尝试
- AI 修改了多个文件，想看看自己到底改了什么 → `diff_with_checkpoint` 逐文件对比

---

### 能力 12：Layout 渲染引擎 — 不用设备就能"看到"UI

**本质：** Android Studio 内置 `layoutlib`——一个在桌面 JVM 上运行的 Android 渲染引擎，能把 XML 布局渲染成图片，无需真机或模拟器。

**为什么 CLI 做不到：** `layoutlib` 虽然理论上是一个库，但它需要完整的 Android SDK 资源、主题系统、字体渲染环境等，独立使用极其复杂。Android Studio 已经完成了所有这些集成工作，通过插件 API 可以直接调用渲染管线。而 CLI 没有这个能力（Compose Screenshot Testing 覆盖的是 `@Preview` Composable，不是任意 XML 布局）。

**为什么对 AI Agent 有价值：**
- AI 写完 XML 布局后，完全不知道"长什么样"——颜色对不对、间距合不合理、文字是否溢出
- 渲染成图片后，结合多模态能力（如果 ACP 支持），AI 可以"看到"自己写的 UI 效果
- 实现"写代码 → 渲染 → 看效果 → 调整"的闭环，无需部署到设备

**SDK API：** `RenderService`, `RenderResult`, Android Studio 内部的 `LayoutlibCallback`

**MCP Tool：**

```json
{
  "name": "render_layout",
  "description": "将 XML 布局文件渲染为图片（无需设备/模拟器）",
  "parameters": {
    "layout_file": "布局文件路径（如 res/layout/fragment_main.xml）",
    "device_config": {
      "screen_width_dp": 360,
      "screen_height_dp": 640,
      "density": "xxhdpi",
      "night_mode": false,
      "locale": "zh-CN",
      "api_level": 34
    }
  },
  "returns": {
    "image_base64": "渲染结果图片（PNG base64）",
    "render_warnings": ["渲染过程中的警告"]
  }
}
```

---

### 能力 13：Android Resource 限定符解析 — 跨配置的资源值追踪

**本质：** Android 的资源系统根据设备配置（语言、深色模式、屏幕密度、API 级别等）选择不同的资源文件。例如 `@color/primary` 在浅色模式下是蓝色，在深色模式下可能是浅蓝色。这个解析过程涉及复杂的优先级规则，且资源可能分散在主模块、库模块、AAR 依赖中。

**为什么 CLI 做不到：** `aapt2 dump resources` 只能 dump 已编译 APK 中的资源。在源码阶段，资源分散在几十个目录中（`values/`, `values-night/`, `values-zh/`, `values-v31/` 等），跨模块（app、library、aar）的资源合并和优先级解析是 Android 构建系统的复杂逻辑。CLI 没有工具能在**未编译的源码阶段**回答"这个资源在某种配置下解析到什么值"。

**SDK API：** `ResourceRepository`, `ResourceManager`, `ResourceResolver`

**MCP Tool：**

```json
{
  "name": "resolve_resource_value",
  "description": "解析一个 Android 资源在指定设备配置下的实际值",
  "parameters": {
    "resource_reference": "@color/primary 或 @string/app_name 或 @dimen/margin_large",
    "config": {
      "night_mode": true,
      "locale": "zh-CN",
      "density": "xxhdpi",
      "api_level": 34
    }
  },
  "returns": {
    "resolved_value": "#BB86FC",
    "source_file": "src/main/res/values-night/colors.xml",
    "source_line": 5,
    "all_variants": [
      { "qualifier": "default", "value": "#6200EE", "file": "values/colors.xml" },
      { "qualifier": "night", "value": "#BB86FC", "file": "values-night/colors.xml" },
      { "qualifier": "v31-night", "value": "#D0BCFF", "file": "values-night-v31/colors.xml" }
    ]
  }
}
```

```json
{
  "name": "find_missing_resource_configs",
  "description": "查找资源缺失的配置变体（如有中文翻译但缺少日文翻译）",
  "parameters": {
    "resource_type": "string | color | dimen",
    "required_qualifiers": ["zh-CN", "ja-JP", "night"]
  },
  "returns": [
    { "resource": "@string/welcome_message", "missing": ["ja-JP"] },
    { "resource": "@color/surface", "missing": ["night"] }
  ]
}
```

---

### 能力 14：Scratch File 沙盒 — 在项目环境中安全试运行代码

**本质：** IntelliJ 的 Scratch File 是一个可以**在完整项目 classpath 下编译和运行**的临时文件，不属于项目源码的一部分。它拥有项目所有依赖的访问权限，但不会影响项目本身。

**为什么 CLI 做不到：** 要在 CLI 中运行一个"使用项目所有依赖"的 Kotlin 脚本，你需要手动构建完整的 classpath（收集所有 Gradle 依赖的 jar 路径、编译输出目录等），这非常复杂且易出错。IDE 的 Scratch File 自动继承项目 classpath。

**为什么对 AI Agent 有价值：**
- AI 可以在不修改任何项目文件的情况下，验证一段代码是否能正常工作
- 测试一个库的 API 用法、验证一个算法、检查一个序列化/反序列化逻辑
- **安全隔离**：Scratch File 运行出错不会影响项目状态

**SDK API：** `ScratchFileService`, `ScratchRootType`, `JavaScratchConfiguration`

**MCP Tool：**

```json
{
  "name": "run_scratch",
  "description": "在项目 classpath 环境中运行临时代码片段（不修改项目文件）",
  "parameters": {
    "language": "kotlin | java",
    "code": "fun main() {\n  val gson = Gson()\n  println(gson.toJson(mapOf(\"key\" to \"value\")))\n}",
    "module_context": "使用哪个模块的 classpath（默认 app）"
  },
  "returns": {
    "stdout": "{\"key\":\"value\"}",
    "stderr": "",
    "exit_code": 0,
    "compile_errors": []
  }
}
```

**典型使用场景：**
- AI 不确定某个 API 的用法 → 写个 Scratch 跑一下确认
- AI 生成了一段数据转换逻辑 → 用 Scratch 验证输入输出是否正确
- AI 想测试一个正则表达式是否匹配目标字符串 → Scratch 直接跑

---

### 能力 15：库源码反编译 — 看到第三方依赖的实现细节

**本质：** Android 项目的大量逻辑在第三方库中（Retrofit、Room、Hilt、Compose 等）。这些库通常没有附带源码，只有编译后的 `.class`/`.jar`/`.aar`。IntelliJ 内置的 FernFlower 反编译器能把字节码还原为可读的 Java 源码。

**为什么 CLI 做不到（实际上）：** 严格来说 CLI 有 `cfr`、`procyon` 等反编译工具。但问题在于：(1) 这些工具需要用户手动找到 jar 文件路径，在 Gradle 缓存的深层目录中（`~/.gradle/caches/modules-2/files-2.1/...`），极其麻烦；(2) 反编译后的代码没有与项目的类型系统关联。而 **IDE 的反编译是透明集成的**——PSI 自动把 `.class` 当作可导航的源码，你点击一个库的类名就直接看到反编译结果，无需知道 jar 在哪。

**为什么对 AI Agent 有价值：**
- AI 遇到库的行为不符合预期时，需要看源码确认——但库没有附带源码
- 理解 Retrofit 的注解处理逻辑、Room 的查询生成逻辑、Compose 的重组机制等
- 调试时追踪调用栈进入库代码，需要看到库的实现

**SDK API：** `ClsFileImpl`, FernFlower 反编译 API, `IdeaDecompiler.decompile()`

**MCP Tool：**

```json
{
  "name": "decompile_class",
  "description": "反编译项目依赖中的类（自动定位 jar/aar，无需手动查找路径）",
  "parameters": {
    "qualified_class_name": "com.squareup.retrofit2.Retrofit"
  },
  "returns": {
    "source_code": "反编译后的 Java 源码",
    "artifact": "com.squareup.retrofit2:retrofit:2.9.0",
    "jar_path": "实际 jar 文件路径"
  }
}
```

---

### 能力 16：VFS 文件变更监听 — 实时感知项目文件系统的变化

**本质：** IntelliJ 的 VFS（Virtual File System）维护了一个项目文件系统的**内存镜像**，能实时追踪所有文件的创建、修改、删除事件，并且与文档模型（Document，含未保存的内容）关联。

**为什么 CLI 做不到：** Linux 的 `inotifywait` 能监听文件事件，但：(1) 无法区分"IDE 内部自动保存"和"用户编辑"；(2) 不知道编辑器中的未保存修改；(3) 没有与项目范围关联的过滤能力。VFS 提供的是项目感知的、与编辑状态联动的文件变更流。

**为什么对 AI Agent 有价值：**
- AI 在等待用户操作时（如"请先手动修改配置"），可以监听特定文件的变更然后自动继续
- 检测外部工具（Gradle、ADB 等）生成的文件（如生成的代码、下载的依赖）
- 与 Local History 配合，构建完整的"谁在什么时间改了什么文件"的审计轨迹

**SDK API：** `VirtualFileManager.addVirtualFileListener()`, `BulkFileListener`, `FileDocumentManager`

**MCP Tool：**

```json
{
  "name": "watch_files",
  "description": "监听指定文件/目录的变更事件",
  "parameters": {
    "paths": ["src/main/java/com/example/"],
    "events": ["created", "modified", "deleted"],
    "timeout_seconds": 30
  },
  "returns": {
    "events": [
      { "type": "modified", "file": "UserRepository.kt", "timestamp": "..." }
    ]
  }
}
```

---

### 能力 17：外部注解 — 给没有源码的库标注空安全信息

**本质：** IntelliJ 维护了一套**外部注解系统**（External Annotations），可以在不修改库源码的情况下，为第三方库的 API 添加 `@Nullable`/`@NonNull` 标注。JetBrains 已经为大量常用库（JDK、Android SDK、Guava 等）预置了外部注解。

**为什么 CLI 做不到：** 这些外部注解存储在 IntelliJ 专有格式的 `annotations.xml` 文件中，与 IDE 的数据流分析引擎集成。CLI 工具不知道也不使用这些注解。

**为什么对 AI Agent 有价值：**
- AI 调用一个第三方 Java 库的方法时，方法签名上没有 `@Nullable` 注解（Java 库普遍如此），AI 不知道返回值能否为 null
- 有了外部注解，AI 可以查询"这个库方法的返回值是 nullable 还是 nonnull"，获得确定性答案
- 结合数据流分析（能力 5），形成完整的空安全推理链

**SDK API：** `ExternalAnnotationsManager`, `inferAnnotations()`

**MCP Tool：**

```json
{
  "name": "get_external_annotations",
  "description": "查询第三方库 API 的外部注解（如空安全、线程约束等）",
  "parameters": {
    "qualified_name": "android.database.Cursor#getString"
  },
  "returns": {
    "annotations": [
      { "annotation": "@Nullable", "target": "return_value" },
      { "annotation": "@IntRange(from=0)", "target": "parameter:columnIndex" }
    ],
    "source": "JetBrains external annotations"
  }
}
```

---

## 最终 MCP Tool 集：精简为 12 个正交工具

> 详细的上下文爆炸分析和信息冗余分析见 [mcp-tool-design-analysis.md](./mcp-tool-design-analysis.md)

### 设计原则

1. **信息正交**——每个 Tool 有唯一信息价值，不与其他 Tool 返回重复信息
2. **固定上限**——每次返回 < 4KB（约 2K tokens），通过分页/摘要/TopN 控制
3. **渐进式披露**——先返回摘要，AI 需要时再请求详情
4. **查询式而非倾倒式**——Tool 回答具体问题，不做 data dump

### 12 个 Tool 总览

| # | Tool | 唯一信息 | 合并了哪些原始能力 | 最大返回 |
|---|------|---------|------------------|---------|
| 1 | `resolve_symbol` | 一个位置的精确类型 | — | ~200B |
| 2 | `find_references` | 一个符号的引用/调用/继承列表 | find_usages + call_hierarchy + type_hierarchy | ~3KB |
| 3 | `get_scope` | 一个位置可用的符号列表 | — | ~3.5KB |
| 4 | `refactor` | 语义级代码变换执行结果 | rename + move + extract + safe_delete + change_signature | ~2KB |
| 5 | `analyze_data_flow` | 值的来源/去向/null 状态 | nullability + trace_data_flow + external_annotations | ~2KB |
| 6 | `query_project` | 自适应全景图（小项目含完整类图，大项目压缩为模块摘要） | project_architecture + dependency_graph + module_api_surface + variant_context | Panorama ≤16KB（自适应）/ Detail ~2KB |
| 7 | `query_framework` | 框架详情视图（概要已含在 Digest 中） | framework_view(room/retrofit/hilt/compose) | list ~1.5KB / detail ~1KB |
| 8 | `analyze_quality` | Top N 代码质量问题 | code_health + dead_code + clones + patterns + error_handling | ~3KB |
| 9 | `check_rules` | 架构规则违规列表 | architecture_rules | ~3KB |
| 10 | `structural_search` | 自定义 AST 模式匹配结果 | — | ~3KB |
| 11 | `checkpoint` | Local History 操作 | create + rollback + diff + history | ~4KB |
| 12 | `sandbox` | 执行操作的输出结果 | run_scratch + decompile + render_layout + convert_j2k + batch_fix | ~4KB |

### 信息正交性保证

```
resolve_symbol     → "这是什么类型"
find_references    → "谁在用它"
get_scope          → "这里能用什么"
refactor           → "帮我安全地改代码"
analyze_data_flow  → "这个值从哪来、能不能是 null"
query_project      → "项目结构长什么样"
query_framework    → "框架视角下项目长什么样"
analyze_quality    → "哪里的代码最有问题"
check_rules        → "有没有违反架构规则"
structural_search  → "符合这个模式的代码在哪"
checkpoint         → "保存/回滚进度"
sandbox            → "试运行/转换/反编译"

↑ 12 个问题，互不重叠，各自独立
```

### 建议实现优先级

| 优先级 | Tools | 理由 |
|--------|-------|------|
| **P0** | `resolve_symbol` + `find_references` + `get_scope` | AI 写代码和改代码时每分钟都需要的基础能力 |
| **P0** | `refactor` | 最高频的代码变换操作，AI 最容易改漏的操作 |
| **P0** | `checkpoint` | AI 大规模操作的安全网 |
| **P1** | `query_project` + `query_framework` | AI 接手项目的第一步——理解全貌 |
| **P1** | `analyze_data_flow` | 空安全推理，Android 开发高频需求 |
| **P1** | `structural_search` + `check_rules` | 批量发现问题 + 架构守护 |
| **P2** | `analyze_quality` | 代码质量治理 |
| **P2** | `sandbox` | 试运行/反编译/转换/渲染/批量修复 |
