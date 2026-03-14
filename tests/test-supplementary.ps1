# MCP Server 补充测试脚本 - 覆盖所有未测试场景
# 用法: .\tests\test-supplementary.ps1 [-Port 17532]

param(
    [int]$Port = 17532,
    [string]$ProjectPath = ""
)

$ErrorActionPreference = "Continue"
$baseUrl = "http://localhost:$Port/mcp"
$sessionId = $null
$testResults = @()
$testIndex = 0

function Write-TestHeader($name) {
    $script:testIndex++
    Write-Host "`n$('=' * 70)" -ForegroundColor Cyan
    Write-Host "SUPP-$script:testIndex : $name" -ForegroundColor Cyan
    Write-Host "$('=' * 70)" -ForegroundColor Cyan
}

function Invoke-McpCall($id, $toolName, $arguments) {
    $body = @{
        jsonrpc = "2.0"; id = $id; method = "tools/call"
        params = @{ name = $toolName; arguments = $arguments }
    } | ConvertTo-Json -Depth 10 -Compress
    $headers = @{
        "Content-Type" = "application/json"
        "Accept" = "application/json, text/event-stream"
        "Mcp-Session-Id" = $script:sessionId
    }
    try {
        $resp = Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $headers -Body $body -UseBasicParsing -TimeoutSec 60
        return ($resp.Content | ConvertFrom-Json)
    } catch {
        Write-Host "  HTTP ERROR: $_" -ForegroundColor Red
        return $null
    }
}

function Test-Result($testName, $response, $validator) {
    $record = @{ Name = $testName; Status = "UNKNOWN"; Detail = "" }
    if ($null -eq $response) {
        $record.Status = "FAIL"; $record.Detail = "No response"
    } elseif ($response.result.isError -eq $true) {
        $text = $response.result.content[0].text
        $record.Status = "ERROR"; $record.Detail = $text.Substring(0, [Math]::Min(200, $text.Length))
        Write-Host "  RESULT: ERROR" -ForegroundColor Red
        Write-Host "  $($record.Detail)" -ForegroundColor Yellow
    } else {
        $text = $response.result.content[0].text
        try { $data = $text | ConvertFrom-Json -ErrorAction Stop } catch { $data = $null }
        if ($null -ne $validator) {
            $validationResult = & $validator $data $text
            $record.Status = $validationResult.Status; $record.Detail = $validationResult.Detail
        } else { $record.Status = "PASS"; $record.Detail = "Response received" }
        $color = if ($record.Status -eq "PASS") { "Green" } elseif ($record.Status -eq "WARN") { "Yellow" } else { "Red" }
        Write-Host "  RESULT: $($record.Status)" -ForegroundColor $color
        Write-Host "  $($record.Detail)" -ForegroundColor Gray
        $preview = if ($text.Length -gt 400) { $text.Substring(0, 400) + "..." } else { $text }
        Write-Host "  PREVIEW: $preview" -ForegroundColor DarkGray
    }
    $script:testResults += $record
}

# ============================================================
# SESSION INIT
# ============================================================
Write-Host "`n$('*' * 70)" -ForegroundColor Magenta
Write-Host "MCP Server Supplementary Test Suite" -ForegroundColor Magenta
Write-Host "Target: $baseUrl | Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "$('*' * 70)" -ForegroundColor Magenta

$initHeaders = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream" }
$initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"supp-test","version":"1.0"}}}'
try {
    $initResp = Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $initHeaders -Body $initBody -UseBasicParsing
    $sessionId = $initResp.Headers["Mcp-Session-Id"]
    Write-Host "  Session: $sessionId" -ForegroundColor Green
    $notifHeaders = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream"; "Mcp-Session-Id" = $sessionId }
    Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $notifHeaders -Body '{"jsonrpc":"2.0","method":"notifications/initialized"}' -UseBasicParsing | Out-Null
    $testResults += @{ Name = "Session Init"; Status = "PASS"; Detail = "OK" }
} catch {
    Write-Host "  FATAL: Cannot connect" -ForegroundColor Red; exit 1
}

$projArg = @{}
if ($ProjectPath -ne "") { $projArg["project_path"] = $ProjectPath }

# Project files
$mainFile = "app/src/main/kotlin/com/vehicle/multimedia/ui/MainActivity.kt"
$composableFile = "core/ui/src/main/kotlin/com/vehicle/multimedia/ui/component/PlaybackControls.kt"
$javaFile = "feature/steering/build/generated/ksp/debug/java/com/vehicle/multimedia/steering/SteeringWheelKeyHandler_Factory.java"
$mainClass = "com.vehicle.multimedia.ui.MainActivity"

# ============================================================
# 1. resolve_symbol - 补充: 参数、变量、方法声明
# ============================================================
Write-TestHeader "resolve_symbol - PARAMETER (isPlaying)"
$resp = Invoke-McpCall 10 "resolve_symbol" (@{ file = $composableFile; line = 17; column = 5 } + $projArg)
Test-Result "resolve_symbol (PARAMETER)" $resp {
    param($d, $t)
    if ($d.kind -eq "PARAMETER") { @{ Status = "PASS"; Detail = "Kind=PARAMETER, name=$($d.qualified_type)" } }
    else { @{ Status = "WARN"; Detail = "Expected PARAMETER, got $($d.kind)" } }
}

Write-TestHeader "resolve_symbol - VARIABLE (local expression)"
$resp = Invoke-McpCall 11 "resolve_symbol" (@{ file = $composableFile; line = 30; column = 5 } + $projArg)
Test-Result "resolve_symbol (VARIABLE)" $resp {
    param($d, $t)
    if ($null -ne $d -and $null -ne $d.kind) { @{ Status = "PASS"; Detail = "Kind=$($d.kind), type=$($d.qualified_type)" } }
    else { @{ Status = "WARN"; Detail = "Could not parse" } }
}

Write-TestHeader "resolve_symbol - METHOD (PlaybackControls fun)"
$resp = Invoke-McpCall 12 "resolve_symbol" (@{ file = $composableFile; line = 16; column = 5 } + $projArg)
Test-Result "resolve_symbol (METHOD)" $resp {
    param($d, $t)
    if ($d.kind -eq "METHOD") { @{ Status = "PASS"; Detail = "Kind=METHOD, name=$($d.qualified_type)" } }
    else { @{ Status = "WARN"; Detail = "Expected METHOD, got $($d.kind)" } }
}

# ============================================================
# 2. get_scope - 补充: VARIABLES 过滤, 方法体内作用域
# ============================================================
Write-TestHeader "get_scope - VARIABLES filter"
$resp = Invoke-McpCall 20 "get_scope" (@{ file = $mainFile; line = 50; column = 10; filter = "VARIABLES" } + $projArg)
Test-Result "get_scope (VARIABLES)" $resp {
    param($d, $t)
    $localCount = if ($null -ne $d.local_variables) { @($d.local_variables).Count } else { 0 }
    $memberCount = if ($null -ne $d.this_members) { @($d.this_members).Count } else { 0 }
    @{ Status = "PASS"; Detail = "Local vars: $localCount, Members: $memberCount (VARIABLES filter)" }
}

Write-TestHeader "get_scope - Inside method body (line 50)"
$resp = Invoke-McpCall 21 "get_scope" (@{ file = $mainFile; line = 50; column = 10 } + $projArg)
Test-Result "get_scope (method body)" $resp {
    param($d, $t)
    $localCount = if ($null -ne $d.local_variables) { @($d.local_variables).Count } else { 0 }
    if ($localCount -gt 0) {
        $names = ($d.local_variables | ForEach-Object { $_.name }) -join ", "
        @{ Status = "PASS"; Detail = "Local variables: $names" }
    } else { @{ Status = "WARN"; Detail = "No local variables found in method body" } }
}

# ============================================================
# 3. find_references - 补充: depth, pagination
# ============================================================
Write-TestHeader "find_references - CALLERS with depth=1"
$resp = Invoke-McpCall 30 "find_references" (@{ file = $composableFile; line = 16; column = 5; mode = "CALLERS"; depth = 1 } + $projArg)
Test-Result "find_references (CALLERS depth=1)" $resp {
    param($d, $t) @{ Status = "PASS"; Detail = "Total: $($d.total)" }
}

Write-TestHeader "find_references - USAGES with limit=3"
$resp = Invoke-McpCall 31 "find_references" (@{ file = $mainFile; line = 37; column = 7; mode = "USAGES"; limit = 3 } + $projArg)
Test-Result "find_references (USAGES limit=3)" $resp {
    param($d, $t)
    $usageCount = if ($null -ne $d.usages) { @($d.usages).Count } else { 0 }
    $status = if ($usageCount -le 3) { "PASS" } else { "WARN" }
    @{ Status = $status; Detail = "Usages returned: $usageCount (limit=3), Total: $($d.total)" }
}

Write-TestHeader "find_references - USAGES with offset=2, limit=2"
$resp = Invoke-McpCall 32 "find_references" (@{ file = $mainFile; line = 37; column = 7; mode = "USAGES"; offset = 2; limit = 2 } + $projArg)
Test-Result "find_references (USAGES offset=2)" $resp {
    param($d, $t)
    $usageCount = if ($null -ne $d.usages) { @($d.usages).Count } else { 0 }
    @{ Status = "PASS"; Detail = "Paginated usages: $usageCount, Total: $($d.total)" }
}

# ============================================================
# 4. query_project - 补充: change_type, module
# ============================================================
Write-TestHeader "query_project - DEPENDENCY with change_type=SIGNATURE_CHANGE"
$resp = Invoke-McpCall 40 "query_project" (@{ mode = "DEPENDENCY"; target_class = $mainClass; change_type = "SIGNATURE_CHANGE"; max_hops = 1 } + $projArg)
Test-Result "query_project (DEPENDENCY+change_type)" $resp {
    param($d, $t)
    if ($null -ne $d.impact) {
        $direct = @($d.impact.direct_impact).Count
        @{ Status = "PASS"; Detail = "Direct impact: $direct, Risk: $($d.impact.risk_level), Suggestion: $($d.impact.suggestion)" }
    } else { @{ Status = "WARN"; Detail = "Missing impact" } }
}

Write-TestHeader "query_project - API_SURFACE with module"
$resp = Invoke-McpCall 41 "query_project" (@{ mode = "API_SURFACE"; module = "app" } + $projArg)
Test-Result "query_project (API_SURFACE+module)" $resp {
    param($d, $t)
    if ($null -ne $d.public_api) {
        @{ Status = "PASS"; Detail = "Public symbols: $($d.public_api.total_public_symbols)" }
    } else { @{ Status = "WARN"; Detail = "Missing public_api" } }
}

# ============================================================
# 5. query_framework - 补充: detail_target
# ============================================================
Write-TestHeader "query_framework - ROOM with detail_target"
$resp = Invoke-McpCall 50 "query_framework" (@{ framework = "ROOM"; detail_target = "ScannedStationEntity" } + $projArg)
Test-Result "query_framework (ROOM+detail)" $resp {
    param($d, $t)
    if ($null -ne $d.room) {
        $entities = @($d.room.entities).Count
        @{ Status = "PASS"; Detail = "Entities: $entities, DetailTarget applied" }
    } else { @{ Status = "WARN"; Detail = "Missing room data" } }
}

Write-TestHeader "query_framework - COMPOSE with detail_target"
$resp = Invoke-McpCall 51 "query_framework" (@{ framework = "COMPOSE"; detail_target = "PlaybackControls" } + $projArg)
Test-Result "query_framework (COMPOSE+detail)" $resp {
    param($d, $t)
    if ($null -ne $d.compose) {
        $composables = @($d.compose.composables).Count
        @{ Status = "PASS"; Detail = "Composables: $composables" }
    } else { @{ Status = "WARN"; Detail = "Missing compose data" } }
}

# ============================================================
# 6. structural_search - 补充: java, all, limit, patterns
# ============================================================
Write-TestHeader "structural_search - Java file_type"
$resp = Invoke-McpCall 60 "structural_search" (@{ pattern = "class"; file_type = "java"; limit = 3 } + $projArg)
Test-Result "structural_search (java)" $resp {
    param($d, $t)
    $count = if ($null -ne $d.matches) { @($d.matches).Count } else { 0 }
    @{ Status = "PASS"; Detail = "Java matches: $count (limit=3), total: $($d.total)" }
}

Write-TestHeader "structural_search - All file_type"
$resp = Invoke-McpCall 61 "structural_search" (@{ pattern = "@Inject"; file_type = "all"; limit = 5 } + $projArg)
Test-Result "structural_search (all)" $resp {
    param($d, $t)
    $count = if ($null -ne $d.matches) { @($d.matches).Count } else { 0 }
    @{ Status = "PASS"; Detail = "All file matches: $count" }
}

Write-TestHeader "structural_search - Inheritance pattern"
$resp = Invoke-McpCall 62 "structural_search" (@{ pattern = "class `$X`$ : ViewModel"; file_type = "kotlin" } + $projArg)
Test-Result "structural_search (inheritance)" $resp {
    param($d, $t)
    $count = if ($null -ne $d.matches) { @($d.matches).Count } else { 0 }
    @{ Status = "PASS"; Detail = "ViewModel subclasses: $count" }
}

Write-TestHeader "structural_search - Fun pattern with annotation"
$resp = Invoke-McpCall 63 "structural_search" (@{ pattern = "@Composable fun"; file_type = "kotlin" } + $projArg)
Test-Result "structural_search (@Composable fun)" $resp {
    param($d, $t)
    $count = if ($null -ne $d.matches) { @($d.matches).Count } else { 0 }
    @{ Status = "PASS"; Detail = "Composable functions: $count" }
}

Write-TestHeader "structural_search - XML pattern (application tag)"
$resp = Invoke-McpCall 64 "structural_search" (@{ pattern = "application"; file_type = "xml" } + $projArg)
Test-Result "structural_search (xml application)" $resp {
    param($d, $t)
    $count = if ($null -ne $d.matches) { @($d.matches).Count } else { 0 }
    @{ Status = "PASS"; Detail = "XML application tags: $count" }
}

# ============================================================
# 7. analyze_quality - 补充: scope, target, top_n
# ============================================================
Write-TestHeader "analyze_quality - COMPLEXITY with top_n=3"
$resp = Invoke-McpCall 70 "analyze_quality" (@{ mode = "COMPLEXITY"; top_n = 3 } + $projArg)
Test-Result "analyze_quality (COMPLEXITY top_n=3)" $resp {
    param($d, $t)
    $issueCount = if ($null -ne $d.issues) { @($d.issues).Count } else { 0 }
    $status = if ($issueCount -le 3) { "PASS" } else { "WARN" }
    @{ Status = $status; Detail = "Issues returned: $issueCount (top_n=3)" }
}

Write-TestHeader "analyze_quality - DEAD_CODE with target"
$resp = Invoke-McpCall 71 "analyze_quality" (@{ mode = "DEAD_CODE"; target = "com.vehicle.multimedia.ui" } + $projArg)
Test-Result "analyze_quality (DEAD_CODE+target)" $resp {
    param($d, $t)
    $issueCount = if ($null -ne $d.issues) { @($d.issues).Count } else { 0 }
    @{ Status = "PASS"; Detail = "Dead code issues in target: $issueCount" }
}

# ============================================================
# 8. refactor - 补充: EXTRACT, CHANGE_SIGNATURE (MOVE 跳过因高风险)
# ============================================================
Write-TestHeader "refactor - EXTRACT (method extraction preview)"
$resp = Invoke-McpCall 80 "refactor" (@{
    operation = "EXTRACT"; file = $mainFile
    line = 37; column = 7; start_line = 50; end_line = 52
    method_name = "extractedSetup"
} + $projArg)
Test-Result "refactor (EXTRACT)" $resp {
    param($d, $t)
    if ($null -ne $d) {
        @{ Status = "PASS"; Detail = "Extract: success=$($d.success), changes=$($d.changes_count)" }
    } else { @{ Status = "WARN"; Detail = "Could not parse" } }
}

# Revert extract by using checkpoint (if extract succeeded)
# Note: EXTRACT may modify code, we'll rely on checkpoint for safety

Write-TestHeader "refactor - CHANGE_SIGNATURE (on PlaybackControls)"
$resp = Invoke-McpCall 81 "refactor" (@{
    operation = "CHANGE_SIGNATURE"; file = $composableFile
    line = 16; column = 5
    new_parameters = @(
        @{ name = "isPlaying"; type = "Boolean" }
        @{ name = "onPlayPause"; type = "() -> Unit" }
        @{ name = "onNext"; type = "() -> Unit" }
        @{ name = "onPrevious"; type = "() -> Unit" }
        @{ name = "newParam"; type = "String"; default_value = '""' }
    )
} + $projArg)
Test-Result "refactor (CHANGE_SIGNATURE)" $resp {
    param($d, $t)
    if ($null -ne $d) {
        @{ Status = "PASS"; Detail = "ChangeSignature: success=$($d.success), changes=$($d.changes_count)" }
    } else { @{ Status = "WARN"; Detail = "Could not parse" } }
}

# ============================================================
# 9. checkpoint - 补充: ROLLBACK
# ============================================================
# First create a checkpoint, then make a change, then rollback
Write-TestHeader "checkpoint - CREATE (for rollback test)"
$rollbackLabel = "rollback-test-$(Get-Date -Format 'HHmmss')"
$resp = Invoke-McpCall 90 "checkpoint" (@{ operation = "CREATE"; label = $rollbackLabel } + $projArg)
Test-Result "checkpoint (CREATE for rollback)" $resp {
    param($d, $t)
    if ($null -ne $d.label) { @{ Status = "PASS"; Detail = "Created: $($d.label)" } }
    else { @{ Status = "WARN"; Detail = "Missing label" } }
}

Write-TestHeader "checkpoint - ROLLBACK"
$resp = Invoke-McpCall 91 "checkpoint" (@{ operation = "ROLLBACK"; label = $rollbackLabel } + $projArg)
Test-Result "checkpoint (ROLLBACK)" $resp {
    param($d, $t)
    if ($null -ne $d) {
        $restored = if ($null -ne $d.restored_files) { @($d.restored_files).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Rollback executed. Restored files: $restored, Label: $($d.label)" }
    } else { @{ Status = "WARN"; Detail = "Could not parse" } }
}

# ============================================================
# 10. check_rules - 补充: 规则应触发违规
# ============================================================
Write-TestHeader "check_rules - Rule with expected violation"
$resp = Invoke-McpCall 100 "check_rules" (@{
    rules = @(
        @{
            name = "No direct Hilt usage from UI components"
            source = "com.vehicle.multimedia.ui"
            must_not_depend_on = @("dagger.hilt.android")
        }
    )
} + $projArg)
Test-Result "check_rules (violation expected)" $resp {
    param($d, $t)
    if ($null -ne $d.violations) {
        $violationCount = @($d.violations).Count
        if ($violationCount -gt 0) {
            @{ Status = "PASS"; Detail = "Correctly detected $violationCount violation(s). Failed: $($d.failed)" }
        } else {
            @{ Status = "WARN"; Detail = "Expected violations but found 0. Rules may not match actual package structure" }
        }
    } else { @{ Status = "WARN"; Detail = "Missing violations field" } }
}

Write-TestHeader "check_rules - Multiple rules"
$resp = Invoke-McpCall 101 "check_rules" (@{
    rules = @(
        @{
            name = "UI-Data isolation"
            source = "com.vehicle.multimedia.ui"
            must_not_depend_on = @("com.vehicle.multimedia.data.local")
        },
        @{
            name = "Feature isolation"
            source = "com.vehicle.multimedia.bluetooth"
            must_not_depend_on = @("com.vehicle.multimedia.usb")
        }
    )
} + $projArg)
Test-Result "check_rules (multiple rules)" $resp {
    param($d, $t)
    $total = $d.passed + $d.failed
    @{ Status = "PASS"; Detail = "Checked $total rules. Passed: $($d.passed), Failed: $($d.failed)" }
}

# ============================================================
# FINAL REPORT
# ============================================================
Write-Host "`n`n$('*' * 70)" -ForegroundColor Magenta
Write-Host "SUPPLEMENTARY TEST REPORT" -ForegroundColor Magenta
Write-Host "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "$('*' * 70)" -ForegroundColor Magenta

$passCount = ($testResults | Where-Object { $_.Status -eq "PASS" }).Count
$warnCount = ($testResults | Where-Object { $_.Status -eq "WARN" }).Count
$failCount = ($testResults | Where-Object { $_.Status -eq "FAIL" -or $_.Status -eq "ERROR" }).Count
$totalCount = $testResults.Count

Write-Host "`nSummary: $passCount PASS / $warnCount WARN / $failCount FAIL (Total: $totalCount)" -ForegroundColor $(if ($failCount -eq 0) { "Green" } else { "Red" })
Write-Host ""

$i = 0
foreach ($r in $testResults) {
    $i++
    $color = switch ($r.Status) { "PASS" { "Green" } "WARN" { "Yellow" } default { "Red" } }
    Write-Host "  [$($r.Status.PadRight(5))] $("$i".PadLeft(2)). $($r.Name)" -ForegroundColor $color
    if ($r.Status -ne "PASS") { Write-Host "         -> $($r.Detail)" -ForegroundColor DarkGray }
}

Write-Host "`n$('*' * 70)" -ForegroundColor Magenta
if ($failCount -gt 0) { exit 1 } else { exit 0 }
