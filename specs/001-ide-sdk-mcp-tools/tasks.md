# Tasks: IDE SDK MCP Tools

**Branch**: `001-ide-sdk-mcp-tools` | **Date**: 2026-03-13  
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Data Model**: [data-model.md](./data-model.md)

## Summary

| Metric | Value |
|--------|-------|
| Total Tasks | 75 |
| Phases | 7 |
| Parallel Opportunities | 23 ([P] tasks) + cross-phase parallelism |
| Critical Path Depth | 10 (T-001→T-005→T-010→T-013→T-021→T-022→T-037→T-046→T-090→T-094) |
| Estimated Tools | 12 |

---

## Phase 1 — Setup (项目配置)

- [X] **T-001** Setup build configuration  
  更新 `build.gradle.kts`：升级到 `androidStudio("2025.2.2.7")`、`sinceBuild="252"`、添加 `kotlin("plugin.serialization")`、`plugin("com.intellij.mcpServer")`、`kotlinx-serialization-json` 依赖  
  File: `build.gradle.kts`

- [X] **T-002** Setup plugin.xml  
  在 `plugin.xml` 中声明 `<depends>com.intellij.mcpServer</depends>`，预留 12 个 `<mcpServerTool>` 注册位  
  File: `src/main/resources/META-INF/plugin.xml`

- [X] **T-003** [P] Create source package structure  
  创建 `tools/`、`services/`、`models/args/`、`models/results/`、`formatting/`、`errors/`、`util/` 包结构  
  Dir: `src/main/kotlin/com/androidstudio/mcpserver/`

- [X] **T-004** [P] Create test package structure  
  创建 `tools/`、`services/`、`testdata/` 测试包结构  
  Dir: `src/test/kotlin/com/androidstudio/mcpserver/`

- [X] **T-005** Verify build compiles  
  运行 `./gradlew build` 验证空项目编译通过  
  Depends: T-001, T-002, T-003

---

## Phase 2 — Foundation (基础设施)

> 被所有 Tool 依赖的共享组件。此 Phase 内标记 [P] 的任务可并行。

- [X] **T-010** [P] Implement McpErrorCode enum (FR-017)  
  定义 6 个错误码枚举（indexing_in_progress, symbol_not_found, conflict_detected, timeout, psi_error, invalid_scope），每个含 code + message  
  File: `errors/McpErrorCode.kt`  
  Depends: T-005

- [X] **T-011** [P] Implement ToolException (FR-017)  
  工具异常基类，含 `McpErrorCode` + `data: Map`，提供 `toErrorJson()` 方法生成 `{"error_code","message","data"}` JSON  
  File: `errors/ToolException.kt`  
  Depends: T-010

- [X] **T-012** [P] Implement McpJson config (F5)  
  配置全局 `Json { namingStrategy = SnakeCase, encodeDefaults = true, ignoreUnknownKeys = true }`  
  File: `util/JsonUtils.kt`

- [X] **T-013** [P] Implement PsiUtils (FR-015, FR-016, D2)  
  封装 `readAction()` (快速失败) 和 `smartReadAction()` (等待索引)，自动检查 `DumbService.isDumb()`  
  File: `util/PsiUtils.kt`  
  Depends: T-010, T-011

- [X] **T-014** [P] Implement ProjectUtils  
  文件路径解析 (`findFile`)、`VirtualFile` ↔ 相对路径转换、行列→PSI 偏移量转换  
  File: `util/ProjectUtils.kt`

- [X] **T-015** Implement SizePolicy enum (FR-013)  
  14 项大小上限配置（每工具/模式独立值），对应 data-model.md Response Size Limits 表  
  File: `formatting/SizePolicy.kt`

- [X] **T-016** Implement ResponseFormatter (FR-013, FR-014a)  
  JSON 序列化 → 字节大小检查 → 超限时截断列表为 TopN → 设置 `truncated=true` + `hint` 提示  
  File: `formatting/ResponseFormatter.kt`  
  Depends: T-012, T-015

- [X] **T-017** Foundation unit tests  
  测试 `PsiUtils`（indexing 场景）、`ResponseFormatter`（截断逻辑）、`ToolException`（JSON 序列化）  
  File: `src/test/kotlin/com/androidstudio/mcpserver/util/`, `formatting/`, `errors/`  
  Depends: T-004, T-010~T-016

---

## Phase 3 — P0 MVP Tools (US1: 符号理解 + US2 部分: 安全网)

> Agent 基础能力最小可用集。resolve_symbol + find_references + get_scope + checkpoint。

### 3.1 resolve_symbol (FR-001)

- [X] **T-020** [P] [US1] Define ResolveSymbolArgs + SymbolInfo models  
  `@Serializable` 数据类：ResolveSymbolArgs(file, line, column)、SymbolInfo(qualifiedType, declarationFile, declarationLine, kind)、SymbolKind enum  
  Files: `models/args/ResolveSymbolArgs.kt`, `models/results/SymbolInfo.kt`

- [X] **T-021** [US1] Implement SymbolResolver service  
  使用 `PsiUtils.readAction` → `ProjectUtils.findFile()` → 行列转偏移量 → `PsiReference.resolve()` → 提取 qualifiedType/声明位置/SymbolKind  
  File: `services/SymbolResolver.kt`  
  Depends: T-013, T-014, T-020

- [X] **T-022** [US1] Implement ResolveSymbolTool  
  `AbstractMcpTool<ResolveSymbolArgs>` 薄壳: 解析参数 → 调用 SymbolResolver → ResponseFormatter 格式化 → 返回 Response  
  File: `tools/ResolveSymbolTool.kt`  
  Depends: T-016, T-021

- [X] **T-023** [US1] Register resolve_symbol in plugin.xml  
  `<mcpServerTool implementation="com.androidstudio.mcpserver.tools.ResolveSymbolTool"/>`  
  File: `plugin.xml`  
  Depends: T-022

- [X] **T-024** [US1] Tests for resolve_symbol  
  覆盖: 基础变量解析、泛型嵌套类型、Kotlin 属性、符号不存在、文件不存在、索引中快速失败  
  Files: `services/SymbolResolverTest.kt`, `tools/ResolveSymbolToolTest.kt`  
  Depends: T-022

### 3.2 find_references (FR-002)

- [X] **T-025** [P] [US1] Define FindReferencesArgs + ReferenceResult models  
  FindReferencesArgs(file, line, column, mode, scope, depth, offset, limit)、FindReferencesMode enum、ReferenceResult、UsageInfo、UsageType enum、CallNode、TypeHierarchyInfo、InheritorInfo  
  Files: `models/args/FindReferencesArgs.kt`, `models/results/ReferenceResult.kt`

- [X] **T-026** [US1] Implement ReferenceSearcher service — usages mode  
  `ReferencesSearch.search()` + scope 过滤 + 引用类型分类(call/override/read/write) + 分页(offset/limit)  
  File: `services/ReferenceSearcher.kt`  
  Depends: T-013, T-014, T-025

- [X] **T-027** [US1] Implement ReferenceSearcher — call_hierarchy mode  
  `CallHierarchyBrowser` API → callers/callees 双向 → 深度控制 → 穿越接口边界  
  File: `services/ReferenceSearcher.kt`  
  Depends: T-026

- [X] **T-028** [US1] Implement ReferenceSearcher — type_hierarchy mode  
  `ClassInheritorsSearch` + `PsiClass.getSupers()` → 向上/向下完整层级  
  File: `services/ReferenceSearcher.kt`  
  Depends: T-026

- [X] **T-029** [US1] Implement FindReferencesTool  
  `AbstractMcpTool<FindReferencesArgs>` + mode 路由(D3) → 对应 Service 方法  
  File: `tools/FindReferencesTool.kt`  
  Depends: T-016, T-026, T-027, T-028

- [X] **T-030** [US1] Register find_references + Tests  
  plugin.xml 注册 + 测试: usages 模式(含 Kotlin 属性语法)、call_hierarchy、type_hierarchy、空结果、scope 过滤  
  Depends: T-029

### 3.3 get_scope (FR-003)

- [X] **T-031** [P] [US1] Define GetScopeArgs + ScopeResult models  
  GetScopeArgs(file, line, column, filter)、ScopeFilter enum、ScopeResult(localVariables, thisMembers, extensionFunctions, importedSymbols, truncated)、ScopeSymbol  
  Files: `models/args/GetScopeArgs.kt`, `models/results/ScopeResult.kt`

- [X] **T-032** [US1] Implement ScopeAnalyzer service  
  `PsiScopeProcessor` + `processDeclarations()` → 按 ScopeFilter 过滤 → 分类为 local/this/extension/imported  
  File: `services/ScopeAnalyzer.kt`  
  Depends: T-013, T-014, T-031

- [X] **T-033** [US1] Implement GetScopeTool + Register + Tests  
  Tool 薄壳 + plugin.xml + 测试: Lambda 内嵌套作用域、扩展函数、过滤器、截断  
  Depends: T-016, T-032

### 3.4 checkpoint (FR-011)

- [X] **T-034** [P] [US2] Define CheckpointArgs + CheckpointResult models  
  CheckpointArgs(operation, label, file, targetLabel)、CheckpointOperation enum、CheckpointResult(label, timestamp, entries, diff, restoredFiles)、HistoryEntry  
  Files: `models/args/CheckpointArgs.kt`, `models/results/CheckpointResult.kt`

- [X] **T-035** [US2] Implement CheckpointManager service  
  `LocalHistory.getInstance()` → create(`putSystemLabel`)、history(文件变更列表)、rollback(恢复到标签)、diff(两版本对比)  
  File: `services/CheckpointManager.kt`  
  Depends: T-014, T-034

- [X] **T-036** [US2] Implement CheckpointTool + Register + Tests  
  Tool 薄壳(不需要 ReadAction) + plugin.xml + 测试: create→history→diff→rollback 完整流程、超出保留期警告  
  Depends: T-016, T-035

- [X] **T-037** P0 Integration test  
  端到端: 在测试项目中调用 resolve_symbol → find_references → get_scope → checkpoint 完整流程  
  Depends: T-024, T-030, T-033, T-036

---

## Phase 4 — P1 Tools (US2: 重构 + US3: 项目理解)

### 4.1 refactor (FR-004)

- [X] **T-040** [P] [US2] Define RefactorArgs + RefactorResult models  
  RefactorArgs(operation, file, line?, column?, newName, targetPackage, startLine, endLine, methodName, newParameters)、RefactorOperation enum、ParameterChange、RefactorResult(success, affectedFiles, changesCount, preview, conflicts, checkpointLabel)、ChangePreview  
  Files: `models/args/RefactorArgs.kt`, `models/results/RefactorResult.kt`

- [X] **T-041** [US2] Implement RefactorExecutor — rename  
  `RefactoringFactory.createRename()` → WriteCommandAction → 跨 Java/Kotlin/XML/Manifest 更新 → 返回 affected files  
  File: `services/RefactorExecutor.kt`  
  Depends: T-013, T-014, T-035, T-040

- [X] **T-042** [US2] Implement RefactorExecutor — move  
  `MoveClassesOrPackagesProcessor` → 更新 import/Manifest/导航图  
  File: `services/RefactorExecutor.kt`  
  Depends: T-041

- [X] **T-043** [US2] Implement RefactorExecutor — extract  
  `ExtractMethodProcessor` → 自动推断参数/返回类型 → 方法体预览  
  File: `services/RefactorExecutor.kt`  
  Depends: T-041

- [X] **T-044** [US2] Implement RefactorExecutor — safe_delete + change_signature  
  `SafeDeleteProcessor`(冲突检测) + `ChangeSignatureProcessor`(参数变更+调用点更新)  
  File: `services/RefactorExecutor.kt`  
  Depends: T-041

- [X] **T-045** [US2] Implement auto-checkpoint for write ops (FR-018, D6)  
  `RefactorExecutor` 在 WriteAction 前自动调用 `CheckpointManager.createAutoCheckpoint()`  
  File: `services/RefactorExecutor.kt`  
  Depends: T-035, T-041

- [X] **T-046** [US2] Implement RefactorTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: rename 跨文件、move 跨包、extract、safe_delete 冲突、change_signature 默认值、自动 checkpoint 验证  
  Depends: T-016, T-041~T-045

### 4.2 query_project (FR-006)

- [X] **T-047** [P] [US3] Define QueryProjectArgs + ProjectOverview models  
  QueryProjectArgs(mode, targetClass, changeType, maxHops, module)、QueryProjectMode/ChangeType enum、ProjectOverview 及所有子实体(ModuleInfo, CycleInfo, ImpactAnalysis, CouplingMetric, ApiSurface, LeakyAbstraction, VariantInfo)  
  Files: `models/args/QueryProjectArgs.kt`, `models/results/ProjectOverview.kt`

- [X] **T-048** [US3] Implement ProjectAnalyzer — overview mode  
  `ModuleManager.getModules()` → 模块依赖图 → 架构模式识别 → 入口点扫描 → 自适应压缩(≤16KB)  
  File: `services/ProjectAnalyzer.kt`  
  Depends: T-013, T-047

- [X] **T-049** [US3] Implement ProjectAnalyzer — dependency + change_impact mode  
  类/包级 `DependenciesBuilder` → 循环检测(DFS) → N 跳传播 → 耦合度量化(afferent/efferent/instability) → 受影响测试  
  File: `services/ProjectAnalyzer.kt`  
  Depends: T-048

- [X] **T-050** [US3] Implement ProjectAnalyzer — api_surface + variant mode  
  模块公共/内部符号扫描 → 抽象泄漏检测 → Build Variant 源集/BuildConfig 读取  
  File: `services/ProjectAnalyzer.kt`  
  Depends: T-048

- [X] **T-051** [US3] Implement QueryProjectTool + Register + Tests  
  Tool 薄壳(smartReadAction) + mode 路由 + plugin.xml + 测试: 多模块项目 overview、循环依赖检测、change_impact、api_surface、variant  
  Depends: T-016, T-048~T-050

### 4.3 query_framework (FR-007)

- [X] **T-052** [P] [US3] Define QueryFrameworkArgs + FrameworkViewResult models  
  QueryFrameworkArgs(framework, detailTarget)、FrameworkType enum、FrameworkViewResult + 5 种框架子视图(RoomView, RetrofitView, HiltView, ComposeView, NavigationView) 及全部子实体  
  Files: `models/args/QueryFrameworkArgs.kt`, `models/results/FrameworkViewResult.kt`

- [X] **T-053** [US3] Implement FrameworkAnalyzer — Room + Retrofit  
  `AnnotatedElementsSearch`(@Entity, @Dao, @Database, @GET/@POST 等) + UAST 遍历 → 结构化视图  
  File: `services/FrameworkAnalyzer.kt`  
  Depends: T-013, T-052

- [X] **T-054** [US3] Implement FrameworkAnalyzer — Hilt + Compose + Navigation  
  @Module/@InstallIn/@Composable/@Preview + Navigation XML 解析  
  File: `services/FrameworkAnalyzer.kt`  
  Depends: T-053

- [X] **T-055** [US3] Implement QueryFrameworkTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: Room Entity/DAO/Migration、Retrofit endpoints、Hilt modules  
  Depends: T-016, T-053, T-054

---

## Phase 5 — P2 Tools (US4: 数据流 + US6: 架构守护)

### 5.1 analyze_data_flow (FR-005)

- [X] **T-060** [P] [US4] Define AnalyzeDataFlowArgs + DataFlowResult models  
  AnalyzeDataFlowArgs(file, line, column, mode)、DataFlowMode enum、DataFlowResult(nullability, reason, nullPaths, flowPaths)、FlowPath、FlowStep  
  Files: `models/args/AnalyzeDataFlowArgs.kt`, `models/results/DataFlowResult.kt`

- [X] **T-061** [US4] Implement DataFlowAnalyzer — nullability mode  
  `DfaUtil` + `ExternalAnnotationsManager` → possibly_null/definitely_not_null/definitely_null + 空路径说明  
  File: `services/DataFlowAnalyzer.kt`  
  Depends: T-013, T-060

- [X] **T-062** [US4] Implement DataFlowAnalyzer — forward/backward mode  
  `SliceAnalysisParams` → 前向/后向切片 → FlowPath 提取  
  File: `services/DataFlowAnalyzer.kt`  
  Depends: T-061

- [X] **T-063** [US4] Implement AnalyzeDataFlowTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: Java→Kotlin 互调 null 传播、外部注解、多层值追踪  
  Depends: T-016, T-061, T-062

### 5.2 check_rules (FR-009)

- [X] **T-064** [P] [US6] Define CheckRulesArgs + RuleCheckResult models  
  CheckRulesArgs(rules: List<ArchitectureRule>)、ArchitectureRule(name, source, mustNotDependOn)、RuleCheckResult(passed, failed, violations)、RuleViolation  
  Files: `models/args/CheckRulesArgs.kt`, `models/results/RuleCheckResult.kt`

- [X] **T-065** [US6] Implement RuleChecker service  
  `DependencyValidationManager` + import/引用分析 → glob 模式匹配 → 违规收集 + 修复建议  
  File: `services/RuleChecker.kt`  
  Depends: T-013, T-064

- [X] **T-066** [US6] Implement CheckRulesTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: domain-independence 规则违规、多规则批量检查  
  Depends: T-016, T-065

### 5.3 structural_search (FR-010)

- [X] **T-067** [P] [US6] Define StructuralSearchArgs + SearchMatchResult models  
  StructuralSearchArgs(pattern, fileType, scope, typeConstraint, limit)、SearchMatchResult(total, matches)、SearchMatch  
  Files: `models/args/StructuralSearchArgs.kt`, `models/results/SearchMatchResult.kt`

- [X] **T-068** [US6] Implement StructuralSearcher service  
  `StructuralSearchProfile` + `Matcher` + `MatchOptions` → SSR 模式编译 → 匹配执行 → 结果提取  
  File: `services/StructuralSearcher.kt`  
  Depends: T-013, T-067

- [X] **T-069** [US6] Implement StructuralSearchTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: `Thread($arg$).start()` 精确匹配、类型约束过滤、Kotlin 语法  
  Depends: T-016, T-068

---

## Phase 6 — P3 Tools (US5: 质量分析 + US7: 沙盒)

### 6.1 analyze_quality (FR-008)

- [X] **T-070** [P] [US5] Define AnalyzeQualityArgs + QualityReport models  
  AnalyzeQualityArgs(mode, scope, target, topN)、QualityMode enum、QualityReport(mode, issues)、QualityIssue  
  Files: `models/args/AnalyzeQualityArgs.kt`, `models/results/QualityReport.kt`

- [X] **T-071** [US5] Implement QualityAnalyzer — complexity + dead_code  
  `CyclomaticComplexityVisitor` → TopN 热点 + `GlobalInspectionTool` unused 系列 → 死代码检测  
  File: `services/QualityAnalyzer.kt`  
  Depends: T-013, T-070

- [X] **T-072** [US5] Implement QualityAnalyzer — clones + patterns + error_handling  
  `DuplicatesProfile` AST 克隆检测 + 设计模式/反模式识别 + 空 catch 异常分析  
  File: `services/QualityAnalyzer.kt`  
  Depends: T-071

- [X] **T-073** [US5] Implement AnalyzeQualityTool + Register + Tests  
  Tool 薄壳(smartReadAction) + plugin.xml + 测试: God Class 热点、代码克隆、空 catch、Feature Envy  
  Depends: T-016, T-071, T-072

### 6.2 sandbox (FR-012)

- [X] **T-074** [P] [US7] Define SandboxArgs + SandboxResult models  
  SandboxArgs(operation + 各模式参数)、SandboxOperation enum、DeviceConfig、SandboxResult(stdout/sourceCode/imageBase64/kotlinCode/problemsFound + checkpointLabel + timedOut)、UnfixableItem  
  Files: `models/args/SandboxArgs.kt`, `models/results/SandboxResult.kt`

- [X] **T-075** [US7] Implement SandboxExecutor — scratch  
  `ScratchFileService` → 创建临时文件 → 项目 classpath 编译运行 → 超时控制(默认 30s) → stdout/stderr 捕获  
  File: `services/SandboxExecutor.kt`  
  Depends: T-014, T-074

- [X] **T-076** [US7] Implement SandboxExecutor — decompile  
  `IdeaDecompiler` → 类名解析 → 反编译 → 源码 + artifact 信息 + 合规提示  
  File: `services/SandboxExecutor.kt`  
  Depends: T-075

- [X] **T-077** [US7] Implement SandboxExecutor — render  
  `RenderService`*(需 Android 插件依赖) → XML 布局 + DeviceConfig → PNG 图片 + 渲染警告  
  File: `services/SandboxExecutor.kt`  
  Depends: T-075

- [X] **T-078** [US7] Implement SandboxExecutor — convert_j2k + batch_fix  
  `J2kConverterExtension` J2K 转换 + `InspectionEngine` 批量 QuickFix(dry_run 预览 + 执行) + 自动 checkpoint  
  File: `services/SandboxExecutor.kt`  
  Depends: T-035, T-075

- [X] **T-079** [US7] Implement SandboxTool + Register + Tests  
  Tool 薄壳(按子操作区分 ReadAction 策略) + plugin.xml + 测试: scratch 超时、decompile、render、j2k、batch_fix dry_run  
  Depends: T-016, T-075~T-078

---

## Phase 7 — Polish (集成与验证)

- [X] **T-090** Full plugin.xml verification  
  验证全部 12 个 `<mcpServerTool>` 注册正确，`<depends>` 声明完整  
  File: `plugin.xml`  
  Depends: All Phase 3~6 Register tasks

- [ ] **T-091** [P] End-to-end integration test — US1 flow  
  Agent 模拟: resolve_symbol → find_references(3 种模式) → get_scope → 验证结果一致性  
  Depends: T-037

- [ ] **T-092** [P] End-to-end integration test — US2 flow  
  Agent 模拟: checkpoint.create → refactor.rename → 验证跨文件更新 → checkpoint.rollback → 验证恢复  
  Depends: T-046

- [ ] **T-093** [P] End-to-end integration test — US3 flow  
  Agent 模拟: query_project.overview → query_framework.room → query_project.dependency  
  Depends: T-051, T-055

- [ ] **T-094** [P] End-to-end integration test — Performance (SC-004)  
  在 500+ 类测试项目中验证: ≤5s(200 类) / ≤30s(500+ 类) / 超时返回 partial_result  
  Depends: T-037, T-051

- [ ] **T-095** Error handling integration test (FR-017)  
  验证全部 6 个 error_code: indexing_in_progress(mock DumbService)、symbol_not_found、conflict_detected、timeout、psi_error、invalid_scope  
  Depends: T-037

- [ ] **T-096** Response size verification (FR-013)  
  对每个工具注入大量数据，验证 ResponseFormatter 按 SizePolicy 截断，truncated + hint 正确  
  Depends: T-037, T-051

- [ ] **T-097** Update quickstart.md  
  更新开发快速入门文档，确保与最终实现一致  
  File: `specs/001-ide-sdk-mcp-tools/quickstart.md`  
  Depends: T-090

---

## Dependency Graph (Critical Path)

```
Phase 1 (T-001~T-005)
  └─▶ Phase 2 (T-010~T-017) — Foundation
       ├─▶ Phase 3 (T-020~T-037) — P0: MVP ─────────────────┐
       │    ├─ resolve_symbol (T-020~T-024)  [P]              │
       │    ├─ find_references (T-025~T-030) [P]              │
       │    ├─ get_scope (T-031~T-033)       [P]              │
       │    ├─ checkpoint (T-034~T-036)      [P]              │
       │    └─ P0 Integration (T-037) ────────────────────────┤
       │                                                       │
       ├─▶ Phase 4 (T-040~T-055) — P1 ────── depends T-035 ──┤
       │    ├─ refactor (T-040~T-046)        [P with 4.2,4.3] │
       │    ├─ query_project (T-047~T-051)   [P with 4.1,4.3] │
       │    └─ query_framework (T-052~T-055) [P with 4.1,4.2] │
       │                                                       │
       ├─▶ Phase 5 (T-060~T-069) — P2 ─── [P with Phase 4] ──┤
       │    ├─ analyze_data_flow (T-060~T-063) [P]             │
       │    ├─ check_rules (T-064~T-066)       [P]             │
       │    └─ structural_search (T-067~T-069) [P]             │
       │                                                       │
       ├─▶ Phase 6 (T-070~T-079) — P3 ─── [P with Phase 5] ──┤
       │    ├─ analyze_quality (T-070~T-073) [P]               │
       │    └─ sandbox (T-074~T-079)         [P]               │
       │                                                       │
       └─▶ Phase 7 (T-090~T-097) — Polish ◄───────────────────┘
```

**关键路径**: T-001 → T-013 → T-021 → T-022 → T-037 → T-046 → T-090 → T-094  
**最大并行度**: Phase 3 内 4 个工具可完全并行 | Phase 4/5/6 之间各工具可并行

## Cross-Phase Parallelism Notes

1. Phase 4/5/6 的 **Models 定义任务**（T-040, T-047, T-052, T-060, T-064, T-067, T-070, T-074）全部标记 [P]，可在 Phase 2 完成后立即并行启动
2. 每个工具内部遵循 **Models → Service → Tool → Test** 串行顺序
3. Phase 5 和 Phase 6 不依赖 Phase 4（仅依赖 Phase 2 Foundation），可与 Phase 4 并行执行
4. `refactor` (T-041) 和 `sandbox.batch_fix` (T-078) 依赖 `checkpoint` (T-035)——写操作安全网前置
