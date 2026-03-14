# MCP Server 全面测试脚本
# 测试所有 12 个工具及其子模式
# 用法: .\tests\test-all-tools.ps1 [-Port 17532] [-ProjectPath ""]

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
    Write-Host "TEST $script:testIndex : $name" -ForegroundColor Cyan
    Write-Host "$('=' * 70)" -ForegroundColor Cyan
}

function Invoke-McpCall($id, $toolName, $arguments) {
    $body = @{
        jsonrpc = "2.0"
        id = $id
        method = "tools/call"
        params = @{
            name = $toolName
            arguments = $arguments
        }
    } | ConvertTo-Json -Depth 10 -Compress

    $headers = @{
        "Content-Type" = "application/json"
        "Accept" = "application/json, text/event-stream"
        "Mcp-Session-Id" = $script:sessionId
    }

    try {
        $resp = Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $headers -Body $body -UseBasicParsing -TimeoutSec 60
        $json = $resp.Content | ConvertFrom-Json
        return $json
    } catch {
        Write-Host "  HTTP ERROR: $_" -ForegroundColor Red
        return $null
    }
}

function Test-Result($testName, $response, $validator) {
    $record = @{ Name = $testName; Status = "UNKNOWN"; Detail = "" }

    if ($null -eq $response) {
        $record.Status = "FAIL"
        $record.Detail = "No response (HTTP error)"
        Write-Host "  RESULT: FAIL (HTTP error)" -ForegroundColor Red
    } elseif ($response.result.isError -eq $true) {
        $text = $response.result.content[0].text
        $record.Status = "ERROR"
        $record.Detail = $text.Substring(0, [Math]::Min(200, $text.Length))
        Write-Host "  RESULT: ERROR" -ForegroundColor Red
        Write-Host "  $($record.Detail)" -ForegroundColor Yellow
    } else {
        $text = $response.result.content[0].text
        try {
            $data = $text | ConvertFrom-Json -ErrorAction Stop
        } catch {
            $data = $null
        }

        if ($null -ne $validator) {
            $validationResult = & $validator $data $text
            $record.Status = $validationResult.Status
            $record.Detail = $validationResult.Detail
        } else {
            $record.Status = "PASS"
            $record.Detail = "Response received"
        }

        if ($record.Status -eq "PASS") {
            Write-Host "  RESULT: PASS" -ForegroundColor Green
        } elseif ($record.Status -eq "WARN") {
            Write-Host "  RESULT: WARN" -ForegroundColor Yellow
        } else {
            Write-Host "  RESULT: $($record.Status)" -ForegroundColor Red
        }
        Write-Host "  $($record.Detail)" -ForegroundColor Gray

        $preview = if ($text.Length -gt 500) { $text.Substring(0, 500) + "..." } else { $text }
        Write-Host "  RESPONSE PREVIEW:" -ForegroundColor DarkGray
        Write-Host "  $preview" -ForegroundColor DarkGray
    }

    $script:testResults += $record
}

# ============================================================
# SESSION INITIALIZATION
# ============================================================
Write-Host "`n$('*' * 70)" -ForegroundColor Magenta
Write-Host "MCP Server Comprehensive Test Suite" -ForegroundColor Magenta
Write-Host "Target: $baseUrl" -ForegroundColor Magenta
Write-Host "Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host "$('*' * 70)" -ForegroundColor Magenta

Write-TestHeader "MCP Session Initialize"
$initHeaders = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream" }
$initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"comprehensive-test","version":"1.0"}}}'
try {
    $initResp = Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $initHeaders -Body $initBody -UseBasicParsing
    $sessionId = $initResp.Headers["Mcp-Session-Id"]
    $initData = $initResp.Content | ConvertFrom-Json
    Write-Host "  Session ID: $sessionId" -ForegroundColor Green
    Write-Host "  Server: $($initData.result.serverInfo.name) v$($initData.result.serverInfo.version)" -ForegroundColor Green

    $notifHeaders = @{ "Content-Type" = "application/json"; "Accept" = "application/json, text/event-stream"; "Mcp-Session-Id" = $sessionId }
    $notifBody = '{"jsonrpc":"2.0","method":"notifications/initialized"}'
    Invoke-WebRequest -Uri $baseUrl -Method POST -Headers $notifHeaders -Body $notifBody -UseBasicParsing | Out-Null
    Write-Host "  Initialized notification sent (202)" -ForegroundColor Green
    $testResults += @{ Name = "Session Init"; Status = "PASS"; Detail = "Session $sessionId" }
} catch {
    Write-Host "  FATAL: Cannot connect to MCP server at $baseUrl" -ForegroundColor Red
    Write-Host "  $_" -ForegroundColor Red
    $testResults += @{ Name = "Session Init"; Status = "FAIL"; Detail = "$_" }
    exit 1
}

# Helper: build project_path arg if specified
$projArg = @{}
if ($ProjectPath -ne "") { $projArg["project_path"] = $ProjectPath }

# ============================================================
# Project-specific file paths (auto-detected from project)
# ============================================================
$mainActivityFile = "app/src/main/kotlin/com/vehicle/multimedia/ui/MainActivity.kt"
$composableFile = "core/ui/src/main/kotlin/com/vehicle/multimedia/ui/component/PlaybackControls.kt"
$navigationFile = "app/src/main/kotlin/com/vehicle/multimedia/ui/navigation/AppNavigation.kt"
$javaFile = "feature/steering/build/generated/ksp/debug/java/com/vehicle/multimedia/steering/SteeringWheelKeyHandler_Factory.java"
$mainActivityClass = "com.vehicle.multimedia.ui.MainActivity"

# ============================================================
# 1. RESOLVE_SYMBOL
# ============================================================
Write-TestHeader "resolve_symbol - Class import (line 3)"
$args1 = @{ file = $mainActivityFile; line = 3; column = 7 } + $projArg
$resp = Invoke-McpCall 10 "resolve_symbol" $args1
Test-Result "resolve_symbol (import)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.qualified_type) {
        @{ Status = "PASS"; Detail = "QualifiedType: $($data.qualified_type), Kind: $($data.kind)" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "Response parsed but missing expected fields. Raw: $($text.Substring(0, [Math]::Min(200, $text.Length)))" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse response" }
    }
}

Write-TestHeader "resolve_symbol - Package declaration (line 1)"
$args2 = @{ file = $mainActivityFile; line = 1; column = 10 } + $projArg
$resp = Invoke-McpCall 11 "resolve_symbol" $args2
Test-Result "resolve_symbol (package)" $resp {
    param($data, $text)
    if ($null -ne $data -and $data.kind -eq "PACKAGE") {
        @{ Status = "PASS"; Detail = "Kind correctly identified as PACKAGE. QualifiedType: $($data.qualified_type)" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "Kind=$($data.kind), expected PACKAGE. QualifiedType: $($data.qualified_type)" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse response" }
    }
}

Write-TestHeader "resolve_symbol - Class declaration (line 37)"
$args3 = @{ file = $mainActivityFile; line = 37; column = 7 } + $projArg
$resp = Invoke-McpCall 12 "resolve_symbol" $args3
Test-Result "resolve_symbol (class decl)" $resp {
    param($data, $text)
    if ($null -ne $data -and $data.kind -eq "CLASS") {
        @{ Status = "PASS"; Detail = "QualifiedType: $($data.qualified_type), Kind: $($data.kind)" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "Kind=$($data.kind), QualifiedType: $($data.qualified_type)" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse response" }
    }
}

Write-TestHeader "resolve_symbol - Annotation (@Inject, line 39)"
$args3b = @{ file = $mainActivityFile; line = 39; column = 7 } + $projArg
$resp = Invoke-McpCall 13 "resolve_symbol" $args3b
Test-Result "resolve_symbol (annotation)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.qualified_type) {
        @{ Status = "PASS"; Detail = "QualifiedType: $($data.qualified_type), Kind: $($data.kind)" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse response" }
    }
}

# ============================================================
# 2. GET_SCOPE
# ============================================================
Write-TestHeader "get_scope - All symbols at class body (line 40)"
$args4 = @{ file = $mainActivityFile; line = 40; column = 5 } + $projArg
$resp = Invoke-McpCall 20 "get_scope" $args4
Test-Result "get_scope (ALL)" $resp {
    param($data, $text)
    $hasLocal = $null -ne $data.local_variables
    $hasMembers = $null -ne $data.this_members
    $hasImports = $null -ne $data.imported_symbols
    $memberCount = if ($hasMembers) { @($data.this_members).Count } else { 0 }
    $importCount = if ($hasImports) { @($data.imported_symbols).Count } else { 0 }
    if ($hasMembers -or $hasImports) {
        @{ Status = "PASS"; Detail = "Members: $memberCount, Imports: $importCount, HasLocal: $hasLocal" }
    } else {
        @{ Status = "WARN"; Detail = "Missing scope data" }
    }
}

Write-TestHeader "get_scope - Filter METHODS"
$args5 = @{ file = $mainActivityFile; line = 40; column = 5; filter = "METHODS" } + $projArg
$resp = Invoke-McpCall 21 "get_scope" $args5
Test-Result "get_scope (METHODS)" $resp {
    param($data, $text)
    $hasMembers = $null -ne $data.this_members
    $memberCount = if ($hasMembers) { @($data.this_members).Count } else { 0 }
    if ($hasMembers) {
        @{ Status = "PASS"; Detail = "Found $memberCount method entries with METHODS filter" }
    } else {
        @{ Status = "WARN"; Detail = "Missing scope data" }
    }
}

Write-TestHeader "get_scope - Filter TYPES"
$args5b = @{ file = $mainActivityFile; line = 40; column = 5; filter = "TYPES" } + $projArg
$resp = Invoke-McpCall 22 "get_scope" $args5b
Test-Result "get_scope (TYPES)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        @{ Status = "PASS"; Detail = "Types filter applied" }
    } else {
        @{ Status = "WARN"; Detail = "Missing scope data" }
    }
}

# ============================================================
# 3. FIND_REFERENCES (4 modes)
# ============================================================
Write-TestHeader "find_references - USAGES (class)"
$args6 = @{ file = $mainActivityFile; line = 37; column = 7; mode = "USAGES" } + $projArg
$resp = Invoke-McpCall 30 "find_references" $args6
Test-Result "find_references (USAGES)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $usageCount = if ($null -ne $data.usages) { @($data.usages).Count } else { 0 }
        $total = $data.total
        @{ Status = "PASS"; Detail = "Usages: $usageCount, Total: $total" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "find_references - CALLERS (PlaybackControls composable)"
$args7 = @{ file = $composableFile; line = 16; column = 5; mode = "CALLERS" } + $projArg
$resp = Invoke-McpCall 31 "find_references" $args7
Test-Result "find_references (CALLERS)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $total = $data.total
        @{ Status = "PASS"; Detail = "Callers analysis completed. Total: $total" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "find_references - CALLEES (method in PlaybackControls)"
$args8 = @{ file = $composableFile; line = 16; column = 5; mode = "CALLEES" } + $projArg
$resp = Invoke-McpCall 32 "find_references" $args8
Test-Result "find_references (CALLEES)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $usageCount = if ($null -ne $data.usages) { @($data.usages).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Callees found: $usageCount" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "find_references - TYPE_HIERARCHY (MainActivity)"
$args9 = @{ file = $mainActivityFile; line = 37; column = 7; mode = "TYPE_HIERARCHY" } + $projArg
$resp = Invoke-McpCall 33 "find_references" $args9
Test-Result "find_references (TYPE_HIERARCHY)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.type_hierarchy) {
        $supersCount = if ($null -ne $data.type_hierarchy.supers) { @($data.type_hierarchy.supers).Count } else { 0 }
        $inheritorsCount = if ($null -ne $data.type_hierarchy.inheritors) { @($data.type_hierarchy.inheritors).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Target: $($data.type_hierarchy.target), Supers: $supersCount, Inheritors: $inheritorsCount" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "Missing type_hierarchy field. Keys: $(($data | Get-Member -Type NoteProperty).Name -join ', ')" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# 4. QUERY_PROJECT (4 modes)
# ============================================================
Write-TestHeader "query_project - OVERVIEW"
$args10 = @{ mode = "OVERVIEW" } + $projArg
$resp = Invoke-McpCall 40 "query_project" $args10
Test-Result "query_project (OVERVIEW)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $hasLevel = $null -ne $data.level
        $hasModules = $null -ne $data.modules
        $hasFrameworks = $null -ne $data.frameworks
        $hasClassMap = $null -ne $data.class_map
        $hasKeyClasses = $null -ne $data.key_classes
        $projectClasses = $data.project_classes
        $detail = "level=$($data.level), classes=$projectClasses, modules=$hasModules, frameworks=$hasFrameworks, classMap=$hasClassMap, keyClasses=$hasKeyClasses"
        if ($hasModules) { $detail += ", moduleCount=$(@($data.modules).Count)" }
        $status = if ($hasLevel -and $hasModules) { "PASS" } else { "WARN" }
        @{ Status = $status; Detail = "Adaptive panorama: $detail" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "query_project - DEPENDENCY"
$args11 = @{ mode = "DEPENDENCY"; target_class = $mainActivityClass; max_hops = 2 } + $projArg
$resp = Invoke-McpCall 41 "query_project" $args11
Test-Result "query_project (DEPENDENCY)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.impact) {
        $direct = @($data.impact.direct_impact).Count
        $transKeys = if ($null -ne $data.impact.transitive_impact) { ($data.impact.transitive_impact | Get-Member -Type NoteProperty).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Direct impact: $direct, Transitive hops: $transKeys, Risk: $($data.impact.risk_level)" }
    } else {
        @{ Status = "WARN"; Detail = "Missing impact data" }
    }
}

Write-TestHeader "query_project - API_SURFACE"
$args12 = @{ mode = "API_SURFACE" } + $projArg
$resp = Invoke-McpCall 42 "query_project" $args12
Test-Result "query_project (API_SURFACE)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.public_api) {
        @{ Status = "PASS"; Detail = "Total public symbols: $($data.public_api.total_public_symbols), Classes: $(@($data.public_api.classes).Count)" }
    } else {
        @{ Status = "WARN"; Detail = "Missing public_api field. Keys: $(($data | Get-Member -Type NoteProperty).Name -join ', ')" }
    }
}

Write-TestHeader "query_project - VARIANT"
$args13 = @{ mode = "VARIANT" } + $projArg
$resp = Invoke-McpCall 43 "query_project" $args13
Test-Result "query_project (VARIANT)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.variant) {
        @{ Status = "PASS"; Detail = "Variant: $($data.variant.variant), BuildType: $($data.variant.buildType)" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "Variant field missing (may not be Android project)" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# 5. QUERY_FRAMEWORK (5 frameworks)
# ============================================================
foreach ($fw in @("ROOM", "RETROFIT", "HILT", "COMPOSE", "NAVIGATION")) {
    Write-TestHeader "query_framework - $fw"
    $fwArgs = @{ framework = $fw } + $projArg
    $resp = Invoke-McpCall (50 + $testIndex) "query_framework" $fwArgs
    Test-Result "query_framework ($fw)" $resp {
        param($data, $text)
        if ($null -ne $data -and $null -ne $data.framework) {
            @{ Status = "PASS"; Detail = "Framework: $($data.framework), HasSummary: $($null -ne $data.summary)" }
        } else {
            @{ Status = "WARN"; Detail = "Incomplete data" }
        }
    }
}

# ============================================================
# 6. STRUCTURAL_SEARCH (Kotlin, Java, XML)
# ============================================================
Write-TestHeader "structural_search - Kotlin class pattern"
$args14 = @{ pattern = "class `$X`$"; file_type = "kotlin" } + $projArg
$resp = Invoke-McpCall 60 "structural_search" $args14
Test-Result "structural_search (kotlin class)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.matches) {
        @{ Status = "PASS"; Detail = "Matches: $(@($data.matches).Count), truncated: $($data.truncated)" }
    } else {
        @{ Status = "WARN"; Detail = "Missing matches field" }
    }
}

Write-TestHeader "structural_search - Annotation pattern"
$args15 = @{ pattern = "@Composable"; file_type = "kotlin" } + $projArg
$resp = Invoke-McpCall 61 "structural_search" $args15
Test-Result "structural_search (@Composable)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.matches) {
        @{ Status = "PASS"; Detail = "Matches: $(@($data.matches).Count)" }
    } else {
        @{ Status = "WARN"; Detail = "Missing matches field" }
    }
}

Write-TestHeader "structural_search - XML pattern"
$args16 = @{ pattern = "activity"; file_type = "xml" } + $projArg
$resp = Invoke-McpCall 62 "structural_search" $args16
Test-Result "structural_search (xml)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.matches) {
        @{ Status = "PASS"; Detail = "XML matches: $(@($data.matches).Count)" }
    } else {
        @{ Status = "WARN"; Detail = "Missing matches field" }
    }
}

# ============================================================
# 7. ANALYZE_QUALITY (5 modes)
# ============================================================
foreach ($qm in @("COMPLEXITY", "DEAD_CODE", "CLONES", "PATTERNS", "ERROR_HANDLING")) {
    Write-TestHeader "analyze_quality - $qm"
    $qArgs = @{ mode = $qm } + $projArg
    $resp = Invoke-McpCall (70 + $testIndex) "analyze_quality" $qArgs
    Test-Result "analyze_quality ($qm)" $resp {
        param($data, $text)
        if ($null -ne $data) {
            @{ Status = "PASS"; Detail = "Quality analysis completed: mode=$qm" }
        } else {
            @{ Status = "WARN"; Detail = "Could not parse" }
        }
    }
}

# ============================================================
# 8. ANALYZE_DATA_FLOW (4 modes)
# ============================================================
Write-TestHeader "analyze_data_flow - NULLABILITY"
$dfArgs1 = @{ file = $mainActivityFile; line = 40; column = 5; mode = "NULLABILITY" } + $projArg
$resp = Invoke-McpCall 80 "analyze_data_flow" $dfArgs1
Test-Result "analyze_data_flow (NULLABILITY)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $hasNullable = $null -ne $data.is_nullable
        @{ Status = "PASS"; Detail = "Nullability analysis: is_nullable=$hasNullable" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "analyze_data_flow - FORWARD"
$dfArgs2 = @{ file = $mainActivityFile; line = 40; column = 5; mode = "FORWARD" } + $projArg
$resp = Invoke-McpCall 81 "analyze_data_flow" $dfArgs2
Test-Result "analyze_data_flow (FORWARD)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $steps = if ($null -ne $data.flow_steps) { @($data.flow_steps).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Forward flow steps: $steps" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "analyze_data_flow - BACKWARD"
$dfArgs3 = @{ file = $mainActivityFile; line = 40; column = 5; mode = "BACKWARD" } + $projArg
$resp = Invoke-McpCall 82 "analyze_data_flow" $dfArgs3
Test-Result "analyze_data_flow (BACKWARD)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $steps = if ($null -ne $data.flow_steps) { @($data.flow_steps).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Backward flow steps: $steps" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "analyze_data_flow - EXTERNAL_ANNOTATIONS"
$dfArgs4 = @{ file = $mainActivityFile; line = 40; column = 5; mode = "EXTERNAL_ANNOTATIONS" } + $projArg
$resp = Invoke-McpCall 83 "analyze_data_flow" $dfArgs4
Test-Result "analyze_data_flow (EXTERNAL_ANNOTATIONS)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $annoCount = if ($null -ne $data.annotations) { @($data.annotations).Count } else { 0 }
        @{ Status = "PASS"; Detail = "External annotations: $annoCount" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# 9. SANDBOX (3 operations) - before refactor to avoid side effects
# ============================================================
Write-TestHeader "sandbox - DECOMPILE"
$sbArgs1 = @{ operation = "DECOMPILE"; qualified_class_name = $mainActivityClass } + $projArg
$resp = Invoke-McpCall 120 "sandbox" $sbArgs1
Test-Result "sandbox (DECOMPILE)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.source_code) {
        $srcLen = $data.source_code.Length
        @{ Status = "PASS"; Detail = "Decompiled source: $srcLen chars" }
    } elseif ($null -ne $data) {
        $keys = ($data | Get-Member -Type NoteProperty).Name -join ', '
        @{ Status = "WARN"; Detail = "Response received, keys: $keys" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "sandbox - CONVERT_J2K"
$sbArgs2 = @{ operation = "CONVERT_J2K"; java_file = $javaFile } + $projArg
$resp = Invoke-McpCall 121 "sandbox" $sbArgs2
Test-Result "sandbox (CONVERT_J2K)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.kotlin_code) {
        $ktLen = $data.kotlin_code.Length
        @{ Status = "PASS"; Detail = "Kotlin conversion: $ktLen chars" }
    } elseif ($null -ne $data) {
        $keys = ($data | Get-Member -Type NoteProperty).Name -join ', '
        @{ Status = "WARN"; Detail = "Response received, keys: $keys" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "sandbox - BATCH_FIX (dry_run)"
$sbArgs3 = @{ operation = "BATCH_FIX"; dry_run = $true; inspection_ids = @("UnusedImport", "RedundantVisibilityModifier") } + $projArg
$resp = Invoke-McpCall 122 "sandbox" $sbArgs3
Test-Result "sandbox (BATCH_FIX)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $found = $data.problems_found
        $fixed = $data.problems_fixed
        @{ Status = "PASS"; Detail = "Problems found: $found, fixed: $fixed" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# 10. REFACTOR (destructive - placed last among tool tests)
# ============================================================
Write-TestHeader "refactor - SAFE_DELETE (conflict check only)"
$refArgs2 = @{ operation = "SAFE_DELETE"; file = $mainActivityFile; line = 37; column = 7 } + $projArg
$resp = Invoke-McpCall 91 "refactor" $refArgs2
Test-Result "refactor (SAFE_DELETE)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        $hasConflicts = if ($null -ne $data.conflicts) { @($data.conflicts).Count } else { 0 }
        @{ Status = "PASS"; Detail = "Safe delete check: success=$($data.success), conflicts=$hasConflicts" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "refactor - RENAME (real execution + revert)"
$refArgs = @{ operation = "RENAME"; file = $mainActivityFile; line = 37; column = 7; new_name = "MainActivity2" } + $projArg
$resp = Invoke-McpCall 90 "refactor" $refArgs
Test-Result "refactor (RENAME)" $resp {
    param($data, $text)
    if ($null -ne $data -and $data.success -eq $true) {
        @{ Status = "PASS"; Detail = "Renamed successfully. Affected: $($data.changes_count) usages" }
    } elseif ($null -ne $data) {
        @{ Status = "WARN"; Detail = "success=$($data.success)" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# Revert the rename
Write-TestHeader "refactor - RENAME (revert back)"
$revertArgs = @{ operation = "RENAME"; file = $mainActivityFile; line = 37; column = 7; new_name = "MainActivity" } + $projArg
$resp = Invoke-McpCall 92 "refactor" $revertArgs
Test-Result "refactor (RENAME revert)" $resp {
    param($data, $text)
    if ($null -ne $data -and $data.success -eq $true) {
        @{ Status = "PASS"; Detail = "Reverted successfully" }
    } else {
        @{ Status = "WARN"; Detail = "Revert may have failed" }
    }
}

# ============================================================
# 10. CHECKPOINT (CREATE, HISTORY, DIFF)
# ============================================================
Write-TestHeader "checkpoint - CREATE"
$cpLabel = "test-checkpoint-$(Get-Date -Format 'HHmmss')"
$cpArgs1 = @{ operation = "CREATE"; label = $cpLabel } + $projArg
$resp = Invoke-McpCall 100 "checkpoint" $cpArgs1
Test-Result "checkpoint (CREATE)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.label) {
        @{ Status = "PASS"; Detail = "Created checkpoint: $($data.label)" }
    } elseif ($null -ne $data) {
        @{ Status = "PASS"; Detail = "Checkpoint create response received" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "checkpoint - HISTORY (all)"
$cpArgs2 = @{ operation = "HISTORY" } + $projArg
$resp = Invoke-McpCall 101 "checkpoint" $cpArgs2
Test-Result "checkpoint (HISTORY)" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.entries) {
        $count = @($data.entries).Count
        @{ Status = "PASS"; Detail = "History entries: $count" }
    } elseif ($null -ne $data) {
        @{ Status = "PASS"; Detail = "History response received" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "checkpoint - HISTORY (specific file)"
$cpArgs3 = @{ operation = "HISTORY"; file = $mainActivityFile } + $projArg
$resp = Invoke-McpCall 102 "checkpoint" $cpArgs3
Test-Result "checkpoint (HISTORY file)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        @{ Status = "PASS"; Detail = "File history response received" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

Write-TestHeader "checkpoint - DIFF"
$cpArgs4 = @{ operation = "DIFF"; file = $mainActivityFile; target_label = $cpLabel } + $projArg
$resp = Invoke-McpCall 103 "checkpoint" $cpArgs4
Test-Result "checkpoint (DIFF)" $resp {
    param($data, $text)
    if ($null -ne $data) {
        @{ Status = "PASS"; Detail = "Diff response received" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# 11. CHECK_RULES
# ============================================================
Write-TestHeader "check_rules - Architecture rule"
$rulesArgs = @{
    rules = @(
        @{
            name = "UI should not depend on data layer directly"
            source = "com.example.myapplication.ui"
            must_not_depend_on = @("com.example.myapplication.data.local", "com.example.myapplication.data.remote")
        }
    )
} + $projArg
$resp = Invoke-McpCall 110 "check_rules" $rulesArgs
Test-Result "check_rules" $resp {
    param($data, $text)
    if ($null -ne $data -and $null -ne $data.violations) {
        @{ Status = "PASS"; Detail = "Violations found: $(@($data.violations).Count), passed: $($data.passed)" }
    } elseif ($null -ne $data) {
        @{ Status = "PASS"; Detail = "Rules check completed" }
    } else {
        @{ Status = "WARN"; Detail = "Could not parse" }
    }
}

# ============================================================
# FINAL REPORT
# ============================================================
Write-Host "`n`n$('*' * 70)" -ForegroundColor Magenta
Write-Host "COMPREHENSIVE TEST REPORT" -ForegroundColor Magenta
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
    $color = switch ($r.Status) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        default { "Red" }
    }
    $idx = "$i".PadLeft(2, " ")
    Write-Host "  [$($r.Status.PadRight(5))] $idx. $($r.Name)" -ForegroundColor $color
    if ($r.Status -ne "PASS") {
        Write-Host "         -> $($r.Detail)" -ForegroundColor DarkGray
    }
}

Write-Host "`n$('*' * 70)" -ForegroundColor Magenta

# Return exit code
if ($failCount -gt 0) { exit 1 } else { exit 0 }
