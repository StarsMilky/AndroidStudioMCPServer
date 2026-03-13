# Feature Specification: Android Studio IDE SDK MCP Tools

**Feature Branch**: `001-ide-sdk-mcp-tools`  
**Created**: 2026-03-13  
**Status**: Draft  
**Input**: User description: "Android Studio SDK MCP Server - Expose IDE-exclusive PSI and non-PSI capabilities as 12 orthogonal MCP tools for AI Agent code intelligence"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - AI Agent 精确理解代码符号 (Priority: P1)

AI Agent 在 Android 项目中修改代码时，需要精确了解某个符号的类型、声明位置、以及谁在引用它。Agent 通过 `resolve_symbol` 获取精确类型信息，通过 `find_references` 获取所有语义级引用（区分调用、重写、读写），通过 `get_scope` 了解当前位置可用的所有符号。这三个工具为 Agent 提供了"确定性的真相"，消除类型猜测和引用遗漏。

**Why this priority**: 这是 AI Agent 写代码和改代码时每分钟都需要的基础能力。没有精确的符号解析，Agent 会产生幻觉——猜错类型、漏掉引用、使用不存在的变量。

**Independent Test**: 在一个包含泛型、接口继承、Kotlin 扩展函数的 Android 项目中，调用 `resolve_symbol` 验证返回的全限定类型、声明位置和符号类别的正确性；调用 `find_references` 验证返回的引用列表完整且零误报；调用 `get_scope` 验证返回的可用符号列表与 IDE 代码补全一致。

**Acceptance Scenarios**:

1. **Given** 一个 Kotlin 文件中存在 `val adapter = MyListAdapter<Item>()`，**When** Agent 对 `adapter` 调用 `resolve_symbol`，**Then** 返回全限定类型 `com.example.adapter.MyListAdapter<com.example.model.Item>`、声明文件路径、行号和符号类别 `variable`
2. **Given** 一个接口方法 `getData()` 有 3 个调用点和 1 个重写实现，**When** Agent 调用 `find_references`，**Then** 返回 4 条引用，分别标记为 `call`（3 条）和 `override`（1 条），不含文本误匹配
3. **Given** Agent 在 Fragment 的 `onViewCreated` 方法内的 Lambda 表达式中，**When** 调用 `get_scope`，**Then** 返回包含局部变量、外层 Lambda 捕获的变量、Fragment 成员、父类成员、当前导入的扩展函数在内的完整可用符号列表
4. **Given** 一个 Kotlin 属性 `obj.data` 实际调用 `getData()`，**When** Agent 对 `getData()` 方法调用 `find_references`，**Then** 返回结果中包含 `obj.data` 这一属性访问语法引用
5. **Given** 一个带有泛型嵌套类型 `LiveData<Resource<List<User>>>` 的变量，**When** Agent 调用 `resolve_symbol`，**Then** 返回完整的泛型参数链，不丢失内层类型信息
6. **Given** 一个接口 `Repository` 有 2 个实现类 `UserRepositoryImpl` 和 `FakeUserRepository`，**When** Agent 调用 `find_references` 的类型层级模式，**Then** 返回向上的父类/接口链和向下的所有实现类列表
7. **Given** 一个底层工具方法 `formatDate()` 被多层调用（Util → ViewModel → Fragment），**When** Agent 调用 `find_references` 的调用层级模式（callers, depth=3），**Then** 返回穿越接口边界的完整 3 层调用链

---

### User Story 2 - AI Agent 安全执行语义级重构 (Priority: P1)

AI Agent 需要在 Android 项目中执行重命名、移动、提取方法、安全删除等重构操作。`refactor` 工具利用 IDE 的 PSI 语义模型，跨 Java/Kotlin/XML/Manifest/ProGuard 所有文件类型安全地执行重构，保证零遗漏。Agent 在执行重构前可通过 `checkpoint` 工具创建安全检查点，失败后可一键回滚。

**Why this priority**: 重构是 AI Agent 最高频的代码变换操作，也是最容易改漏的操作。文本替换无法处理跨语言（Java/Kotlin/XML/Manifest）的引用更新。

**Independent Test**: 在一个 Activity 被 Manifest、导航图、XML 布局引用的场景中，调用 `refactor` 重命名该 Activity，验证所有引用文件被正确更新；在重构前创建 checkpoint，重构失败后验证能回滚到检查点状态。

**Acceptance Scenarios**:

1. **Given** 一个 Activity 在 AndroidManifest.xml、nav_graph.xml 和 3 个 Kotlin 文件中被引用，**When** Agent 通过 `refactor` 执行重命名操作，**Then** 返回所有被修改的文件列表和变更数量，所有引用均已正确更新
2. **Given** Agent 要提取一段包含外部变量引用的代码为独立方法，**When** 调用 `refactor` 的 extract 操作，**Then** 返回自动推断的参数列表、返回类型和提取后的方法体预览
3. **Given** Agent 要删除一个类，但该类仍被其他地方引用，**When** 调用 `refactor` 的 safe_delete 操作，**Then** 返回冲突报告而非直接删除，列出所有引用点
4. **Given** Agent 已创建了名为 "before-refactoring" 的检查点，且之后的重构操作导致项目编译失败，**When** Agent 调用 `checkpoint` 的 rollback 操作，**Then** 所有文件恢复到检查点状态
5. **Given** 一个方法 `fetchUser(id: Long)` 被 5 个地方调用，Agent 需要添加一个新参数 `includeArchived: Boolean`，**When** 调用 `refactor` 的 change_signature 操作，**Then** 方法签名和所有 5 个调用点均被正确更新，新参数使用指定的默认值
6. **Given** 一个 `UserDetailActivity` 类位于 `com.example.ui.detail` 包中，被 AndroidManifest.xml、nav_graph.xml 和 2 个 Kotlin 文件导入，**When** Agent 调用 `refactor` 的 move 操作将其移至 `com.example.feature.user` 包，**Then** 类文件被移动到新包目录，所有 import 语句、Manifest 中的 `android:name` 和导航图引用均被正确更新
7. **Given** Agent 在过去 1 小时内对 `UserRepository.kt` 做过 3 次修改，**When** Agent 调用 `checkpoint` 的 history 操作查询该文件的修改时间线，**Then** 返回至少 3 条 Local History 记录，每条包含时间戳和变更摘要
8. **Given** Agent 在 2 次提交之间修改了 `UserRepository.kt`，**When** Agent 调用 `checkpoint` 的 diff 操作对比当前版本与指定检查点版本，**Then** 返回两个版本之间的逐行差异（additions/deletions/modifications）

---

### User Story 3 - AI Agent 理解项目全貌与架构 (Priority: P1)

AI Agent 接手一个陌生的 Android 项目时，需要快速了解项目结构——模块划分、模块间依赖、架构分层、入口点、框架使用情况。`query_project` 提供自适应的项目全景图（小项目含完整类图，大项目压缩为模块摘要），`query_framework` 提供特定框架（Room/Retrofit/Hilt/Compose/Navigation）的结构化视图。

**Why this priority**: 理解项目全貌是 AI Agent 接手项目的第一步。没有全景图，Agent 无法做出合理的架构决策。

**Independent Test**: 在一个多模块 Clean Architecture Android 项目中，调用 `query_project` 获取全景图，验证返回的模块列表、依赖方向、架构模式识别的准确性；调用 `query_framework` 获取 Room 视图，验证返回的 Entity、DAO、Migration 信息完整性。

**Acceptance Scenarios**:

1. **Given** 一个包含 app/domain/data 三个模块的 Clean Architecture 项目，**When** Agent 调用 `query_project` 的 overview 模式，**Then** 返回模块列表、模块间依赖关系、识别出的架构模式（如 "Clean Architecture"）、入口点和依赖方向
2. **Given** 项目使用 Room 数据库包含 3 个 Entity 和 2 个 DAO，**When** Agent 调用 `query_framework` 查询 room，**Then** 返回完整的数据库结构（Entity 字段、主键、索引、关系）、DAO 方法（含 SQL 语句和返回类型）和 Migration 列表
3. **Given** 项目当前选中的 Build Variant 为 `freeDebug`，**When** Agent 调用 `query_project` 的 variant 查询，**Then** 返回当前激活的源集目录、非激活目录、合并后的 BuildConfig 字段
4. **Given** 一个超大项目（500+ 类），**When** Agent 调用 `query_project`，**Then** 返回的全景图自动压缩为模块级摘要，总大小不超过 16KB
5. **Given** Agent 修改了 `UserRepository` 类的方法签名，**When** 调用 `query_project` 的变更影响分析模式（change_type=signature_change, max_hops=3），**Then** 返回直接依赖类列表、多跳传播范围、受影响的模块和测试文件、风险等级评估和解耦建议
6. **Given** 项目中存在 ServiceA → ServiceB → ServiceA 的循环依赖，**When** Agent 调用 `query_project` 的依赖关系图模式，**Then** 检测到该循环并标记为 critical 级别
7. **Given** data 模块中 `UserRepositoryImpl` 是 public 但应为 internal，**When** Agent 调用 `query_project` 的 API 表面分析模式，**Then** 返回该类作为"抽象泄漏"问题，建议添加 `internal` 修饰符

---

### User Story 4 - AI Agent 追踪数据流与空安全推理 (Priority: P2)

AI Agent 需要判断变量在某个代码点是否可能为 null，追踪数据从网络层到 UI 层的完整传播路径。`analyze_data_flow` 整合了 IDE 的数据流分析引擎（DFA）、前向/后向切片分析和外部注解系统，提供确定性的空安全推理和值传播追踪。

**Why this priority**: 空安全推理是 Android 开发的高频需求，特别在 Java-Kotlin 互调用和平台类型场景下，AI 无法仅通过读代码可靠判断空安全。

**Independent Test**: 在一个包含 Java-Kotlin 互调用、多层函数传递的场景中，调用 `analyze_data_flow` 的 nullability 模式，验证返回的空安全判断与编译器分析一致；调用 backward 追踪模式，验证返回的值传播路径完整。

**Acceptance Scenarios**:

1. **Given** 一个 Java 方法返回 `@Nullable String`，经过 3 层传递后在 Kotlin 代码中使用，**When** Agent 调用 `analyze_data_flow` 查询空安全，**Then** 返回 `possibly_null`，包含为 null 的代码路径说明
2. **Given** 一个值从 Repository 层经过 UseCase、ViewModel 传递到 Fragment，**When** Agent 调用 `analyze_data_flow` 的 backward 追踪，**Then** 返回从 Fragment 到 Repository 的完整值传播路径
3. **Given** 一个第三方 Java 库方法没有 `@Nullable` 注解，但 JetBrains 外部注解标注了返回值为 nullable，**When** Agent 调用 `analyze_data_flow`，**Then** 返回基于外部注解的准确空安全信息

---

### User Story 5 - AI Agent 批量发现代码质量问题 (Priority: P2)

AI Agent 需要识别项目中的代码质量问题——复杂度热点、死代码、代码克隆、反模式、被吞掉的异常等。`analyze_quality` 整合了代码健康度指标、死代码检测、AST 级别克隆检测、设计模式/反模式识别和异常处理分析，返回 Top N 最严重的质量问题。

**Why this priority**: 代码质量治理需要项目级分析能力，AI 逐文件阅读无法可靠地完成此类分析。

**Independent Test**: 在一个包含已知复杂度热点（圈复杂度 > 40 的方法）、死代码和被吞掉异常的项目中，调用 `analyze_quality`，验证返回的问题列表包含所有已知问题，并按严重程度正确排序。

**Acceptance Scenarios**:

1. **Given** 项目中存在一个圈复杂度为 47 的 God Class，**When** Agent 调用 `analyze_quality` 的 complexity 模式，**Then** 返回该类作为 Top 1 热点，包含复杂度指标、耦合度和拆分建议
2. **Given** 项目中有 3 个结构相同但变量名不同的代码块分布在不同 Repository 文件中，**When** Agent 调用 `analyze_quality` 的 clones 模式，**Then** 检测到该克隆组，返回所有实例位置和重构建议
3. **Given** 项目中有一个空的 `catch (Exception e) {}` 块，**When** Agent 调用 `analyze_quality` 的 error_handling 模式，**Then** 标记该异常为 `critical` 级别并说明上下文
4. **Given** 项目中有一个 public 方法 `legacySync()` 和一个类 `OldUserMapper` 未被任何地方引用，**When** Agent 调用 `analyze_quality` 的 dead_code 模式，**Then** 返回这两个死代码项，包含文件位置、可见性和可安全删除的行数统计
5. **Given** 项目中存在通过接口+sealed class 实现的 State Machine 模式和一个 Feature Envy 反模式（方法访问了 7 个其他类的字段但只用了 1 个自身字段），**When** Agent 调用 `analyze_quality` 的 patterns 模式，**Then** 分别识别出设计模式和反模式，反模式包含量化证据和重构建议

---

### User Story 6 - AI Agent 守护架构规则 (Priority: P2)

AI Agent 需要验证代码是否遵守团队的架构约定（如 "domain 层不能依赖 Android framework"），使用自定义 AST 模式搜索特定代码模式。`check_rules` 遍历项目的 import 和引用关系，检测架构违规；`structural_search` 基于 AST 结构进行模式匹配，超越文本搜索的能力。

**Why this priority**: 架构腐化是渐进式的，只有自动化检测才能持续守护。

**Independent Test**: 定义 "domain 不依赖 android" 和 "ViewModel 不直接依赖 Repository 实现类" 两条规则，在一个有意引入违规的项目中调用 `check_rules`，验证所有违规被检出；使用 `structural_search` 搜索 "所有 `Thread().start()` 调用"，验证结果准确且无误报。

**Acceptance Scenarios**:

1. **Given** domain 模块中有一个类导入了 `android.content.Context`，**When** Agent 调用 `check_rules` 配置 "domain-independence" 规则，**Then** 返回该违规项，含文件名、行号、违规 import 和修复建议
2. **Given** 项目中有 3 处 `Thread($arg$).start()` 调用和 5 处 `Thread` 的其他引用（构造但未 start、注释中的引用等），**When** Agent 调用 `structural_search` 搜索 `Thread($arg$).start()` 模式，**Then** 仅返回 3 处精确匹配，不含误报

---

### User Story 7 - AI Agent 在沙盒中安全试运行和转换 (Priority: P3)

AI Agent 需要在不修改项目文件的情况下验证代码片段、反编译第三方库源码、将 Java 转为 Kotlin、渲染 XML 布局和批量执行 QuickFix。`sandbox` 工具提供了一个安全隔离的执行环境，支持多种操作类型。

**Why this priority**: 沙盒能力提供了"写代码 → 验证 → 调整"的闭环，但相比前述能力优先级较低。

**Independent Test**: 使用 `sandbox` 的 scratch 模式运行一段使用项目依赖的 Kotlin 代码，验证输出正确且项目文件未被修改；使用 decompile 模式反编译 Retrofit 类，验证返回可读的 Java 源码。

**Acceptance Scenarios**:

1. **Given** 项目依赖了 Gson 库，**When** Agent 调用 `sandbox` 的 scratch 模式运行 `Gson().toJson(mapOf("key" to "value"))`，**Then** 返回正确的 stdout 输出 `{"key":"value"}`，项目文件无任何变更
2. **Given** Agent 需要了解 Retrofit 的内部实现，**When** 调用 `sandbox` 的 decompile 模式查询 `com.squareup.retrofit2.Retrofit`，**Then** 返回反编译后的可读 Java 源码、所属 artifact 名称和 jar 路径
3. **Given** 一个 Java 文件包含 `@Nullable` 注解和 SAM 接口使用，**When** Agent 调用 `sandbox` 的 convert_j2k 模式，**Then** 返回惯用 Kotlin 代码，正确处理空安全类型和 Lambda 转换
4. **Given** Agent 编写了一个 XML 布局文件 `fragment_main.xml`，**When** 调用 `sandbox` 的 render 模式，指定设备配置（360dp 宽、xxhdpi、深色模式、zh-CN），**Then** 返回渲染后的 PNG 图片和渲染警告列表，Agent 可直观检查 UI 效果
5. **Given** 项目中有 12 处 Java 匿名类可转为 Lambda、8 处 `var` 可改为 `val`，**When** Agent 调用 `sandbox` 的 batch_fix 模式（dry_run=true），**Then** 返回将要修复的 20 处问题预览，包含每处的文件位置和修复内容；确认后执行时返回实际修复数量和无法自动修复的项

---

### Edge Cases

- **超大项目性能**：当项目包含 1000+ 类时，`query_project` 和 `analyze_quality` 的执行时间和返回大小如何控制？（通过自适应压缩和 TopN 策略，返回不超过 16KB/4KB）
- **并发安全**：采用单 Agent 非独占模式——Agent 操作期间人类可继续编辑 IDE；读操作（resolve_symbol、find_references 等）不阻塞人类交互；写操作（refactor、sandbox.batch_fix）若检测到执行期间目标文件被外部修改，应 graceful fail 并返回冲突描述（不回滚部分变更，改由 checkpoint 机制保障安全）。首期不支持多 Agent 并发连接。PSI 读取操作必须在 ReadAction 中执行（PSI 是线程敏感的）
- **符号解析失败**：当文件存在语法错误或项目未完成 Gradle 同步时，`resolve_symbol` 如何处理？（返回错误信息而非崩溃，说明解析失败的原因）
- **Build Variant 切换**：当用户在 IDE 中切换 Build Variant 时，正在执行的 MCP tool 是否会受影响？（应在工具执行期间锁定当前 Variant 上下文）
- **Local History 存储限制**：IDE 的 Local History 保留期限有限（默认 5 天），`checkpoint` 工具是否应提醒用户？（是，当查询超过保留期的历史时应返回警告）
- **反编译法律合规**：`sandbox` 的 decompile 功能是否需要在返回结果中附带使用警告？（应包含合规提示）
- **Sandbox 代码超时**：`sandbox` 的 scratch 模式运行用户代码可能死循环或长时间挂起，如何处理？（应设置可配置的执行超时，默认 30 秒；超时后强制终止并返回 timeout 错误）
- **Build 生成代码**：`resolve_symbol`/`find_references` 对 Room DAO 实现类、DataBinding 生成类、Hilt 注入类等构建时生成代码的支持如何？（应支持——IDE 在 Gradle 同步后已将生成代码纳入 PSI 索引，与手写代码同等对待）

## Clarifications

### Session 2026-03-13

- Q: MCP Server 主传输协议选型？ → A: 由 IntelliJ 内置 MCP Server 管理传输（默认 stdio），插件仅通过 AbstractMcpTool 扩展点注册工具
- Q: Agent 与人类开发者的并发控制模型？ → A: 单 Agent 非独占模式——人类可编辑 IDE，写操作冲突时 graceful fail，首期不支持多 Agent
- Q: 12 个工具的错误响应是否统一格式？ → A: 通过 `Response(error=JSON)` 返回统一 JSON 结构体（`error_code` + `message` + `data`），MCP 协议层以 `isError=true` 传递给 Agent。error_code 枚举值：indexing_in_progress、symbol_not_found、conflict_detected、timeout、psi_error、invalid_scope
- Q: 大型项目（500+ 类）响应时间上限？ → A: ≤30 秒，超时返回部分结果并附 partial_result 标记
- Q: 写操作（refactor/batch_fix）是否需要人类确认？ → A: 无需确认，Agent 完全自主执行，依赖 checkpoint 机制保障安全回滚能力

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 `resolve_symbol` 工具，接受文件路径、行号和列号，返回该位置符号的全限定类型、声明文件位置和符号类别（class/method/field/variable/parameter/property）
- **FR-002**: 系统 MUST 提供 `find_references` 工具，合并三种语义查询模式：（1）**引用查找**——基于 PSI 语义模型查找符号的所有引用，区分引用类型（call/override/read/write），支持 project/module/file 作用域，结果零误报零漏报；（2）**调用层级**——支持 callers/callees 双向查询，可配置分析深度（默认 3 层），穿越接口边界追踪调用链；（3）**类型层级**——返回类/接口的完整继承层级（向上：父类/接口链，向下：所有子类/实现类）
- **FR-003**: 系统 MUST 提供 `get_scope` 工具，返回指定代码位置的所有可用符号（局部变量、this 成员、扩展函数、导入符号），支持按类型过滤（variables/methods/types）
- **FR-004**: 系统 MUST 提供 `refactor` 工具，支持 rename/move/extract/safe_delete/change_signature 五种操作，跨 Java/Kotlin/XML/Manifest/ProGuard 安全执行，返回受影响文件列表和变更预览
- **FR-005**: 系统 MUST 提供 `analyze_data_flow` 工具，整合空安全分析（nullability）、值传播追踪（forward/backward）和外部注解查询，返回确定性的数据流分析结果
- **FR-006**: 系统 MUST 提供 `query_project` 工具，合并四种子能力：（1）**项目全景图**——返回模块列表、模块间依赖、架构分层、入口点，大项目自适应压缩为模块摘要（≤16KB）；（2）**依赖关系图与变更影响分析**——构建类/包级依赖有向图，支持循环依赖检测、N 跳变更传播分析（需指定变更类型：signature_change/behavior_change/delete，不同类型影响范围不同）、耦合度量化（afferent/efferent coupling, instability）；（3）**模块 API 表面分析**——扫描模块的公共/内部符号，识别抽象泄漏（如实现类被公开暴露）；（4）**Build Variant 感知**——返回当前激活 Variant 下的有效源集目录、BuildConfig 字段。详情模式返回单个子能力的结果（≤2KB）
- **FR-007**: 系统 MUST 提供 `query_framework` 工具，基于注解扫描为 Room/Retrofit/Hilt/Compose/Navigation 生成结构化的框架视图
- **FR-008**: 系统 MUST 提供 `analyze_quality` 工具，整合代码复杂度、死代码检测、AST 级代码克隆检测、设计模式/反模式识别和异常处理分析，返回 Top N 问题
- **FR-009**: 系统 MUST 提供 `check_rules` 工具，接受自定义架构规则定义（source 包/模块 + must_not_depend_on 约束），遍历项目引用关系检测违规
- **FR-010**: 系统 MUST 提供 `structural_search` 工具，支持 IntelliJ SSR 语法的 AST 模式匹配，支持类型约束和变量过滤
- **FR-011**: 系统 MUST 提供 `checkpoint` 工具，支持创建命名检查点（create）、查看历史（history）、回滚到检查点（rollback）和对比差异（diff）四种操作
- **FR-012**: 系统 MUST 提供 `sandbox` 工具，支持在项目 classpath 中运行临时代码（scratch）、反编译第三方类（decompile）、渲染 XML 布局（render）、Java→Kotlin 转换（convert_j2k）和批量执行 Inspection QuickFix（batch_fix）五种操作
- **FR-013**: 每个工具的单次返回大小 MUST 控制在各自上限以内，超出时通过摘要+分页+TopN 策略压缩。各工具上限如下：`resolve_symbol` ≤200B；`find_references` ≤3KB；`get_scope` ≤3.5KB；`refactor` ≤2KB；`analyze_data_flow` ≤2KB；`query_project`（全景模式 ≤16KB，详情模式 ≤2KB）；`query_framework`（列表 ≤1.5KB，详情 ≤1KB）；`analyze_quality` ≤3KB；`check_rules` ≤3KB；`structural_search` ≤3KB；`checkpoint` ≤4KB；`sandbox` ≤4KB
- **FR-014**: 所有工具 MUST 遵循"查询式而非倾倒式"原则——回答具体问题，不做数据转储
- **FR-014a**: 返回复杂结果的工具（`find_references`、`query_project`、`analyze_quality`、`check_rules`）MUST 遵循"渐进式披露"原则——首次调用返回摘要/TopN 概览，AI 需要详情时通过后续调用请求特定子项的完整信息
- **FR-015**: 所有 PSI 读取操作 MUST 在 ReadAction 中执行，写操作 MUST 在 WriteAction 中执行，保证线程安全
- **FR-016**: 当 IDE 未完成项目索引（indexing）时，所有工具 MUST 返回明确的"索引中"状态提示，不返回不完整结果
- **FR-017**: 所有工具的错误响应 MUST 通过 `AbstractMcpTool` 的 `Response(error = String)` 返回，error 字符串内部 MUST 采用统一的 JSON 结构体编码（`{"error_code":"<枚举值>","message":"<人类可读描述>","data":{<上下文信息>}}`）。工具级 error_code 枚举值包括：`indexing_in_progress`、`symbol_not_found`、`conflict_detected`、`timeout`、`psi_error`、`invalid_scope`。MCP 协议层将以 `isError=true` 的 tool response 传递给 AI Agent
- **FR-018**: 所有写操作（`refactor`、`sandbox.batch_fix`、`checkpoint.rollback`）MUST 无需人类确认即可由 Agent 自主执行；安全性由 `checkpoint` 机制保障——Agent 应在执行破坏性写操作前自行调用 `checkpoint.create`

### Key Entities

- **MCP Tool**: 暴露给 AI Agent 的操作单元，每个 Tool 有唯一的信息职责、固定的输入参数 schema 和受控的返回大小
- **PSI Element**: IntelliJ 的程序结构接口元素（类、方法、变量、表达式等），是所有代码分析操作的基础数据模型
- **Symbol**: 代码中的具名实体（类名、方法名、变量名等），通过 PSI 解析可获得其精确类型和声明位置
- **Reference**: 符号的使用点，包含引用类型（call/override/read/write）和位置信息
- **Checkpoint**: Local History 中的命名时间点，作为 AI 操作的安全回滚锚点
- **Framework View**: 特定框架（Room/Retrofit/Hilt 等）的结构化视图，通过注解扫描和 PSI 分析生成

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: AI Agent 通过 `resolve_symbol` 获取的类型信息准确率达到 100%（与 IDE 内部类型解析结果一致）
- **SC-002**: AI Agent 通过 `find_references` 查找引用的误报率为 0%，漏报率为 0%（与 IDE "Find Usages" 结果一致）
- **SC-003**: AI Agent 执行 `refactor` 操作后，项目编译成功率不低于 95%（排除重构前已存在的编译错误）
- **SC-004**: 所有 MCP Tool 的单次调用响应时间在中型项目（200 个类以内）中不超过 5 秒；在大型项目（500+ 类）中不超过 30 秒——超时时应返回已完成的部分结果并附带 `partial_result` 标记
- **SC-005**: `query_project` 全景图在 500+ 类的大型项目中，返回数据大小不超过 16KB，且包含完整的模块间依赖关系
- **SC-006**: `checkpoint` 的 rollback 操作能在 3 秒内恢复到指定检查点状态
- **SC-007**: 12 个工具之间信息正交——每个工具有唯一的信息价值，不与其他工具返回重复信息
- **SC-008**: AI Agent 使用 MCP Tools 后，在大型 Android 项目中执行代码修改任务时，因幻觉导致的错误（猜错类型、漏改引用、使用不存在的符号）减少 80% 以上

### Assumptions

- Android Studio **2025.2+**（IntelliJ platform 252+）已安装并已打开目标 Android 项目
- 项目已完成 Gradle 同步，PSI 索引构建完成
- MCP Server 作为 Android Studio/IntelliJ IDEA 插件运行，拥有完整的 IDE SDK API 访问权限
- 传输层由 IntelliJ 2025.2+ 内置 MCP Server 管理（默认 stdio），插件通过 `AbstractMcpTool` 扩展点注册工具，无需自行实现 JSON-RPC 或传输协议
- 目标项目使用标准的 Android 项目结构（Gradle-based）
- IDE 的 Local History 功能处于启用状态（默认启用）
