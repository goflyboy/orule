<#
.SYNOPSIS
  校验 JUnit 5 测试类是否声明 public class，命中 AGENTS.md STY-J002。
兼容 PowerShell 5.1+ / PowerShell 7+。

.DESCRIPTION
  扫所有 src/test/java/**/*.java，匹配 ^class\s+\w+Test\b（非 public）即视为违规。

.PARAMETER Root
  仓库根路径，默认当前 cwd。

.PARAMETER DryRun
  仅输出，不返回非零退出码。

.PARAMETER Allow
  白名单，格式 path:line 或 path，多个用逗号分隔。

.EXAMPLE
  pwsh -File scripts/check-test-public.ps1
#>

[CmdletBinding()]
param(
    [string]$Root = (Get-Location).Path,
    [switch]$DryRun,
    [string]$Allow = ""
)

$ErrorActionPreference = "Stop"
$allowSet = @{}
if ($Allow) {
    foreach ($entry in $Allow.Split(',')) {
        $allowSet[$entry.Trim()] = $true
    }
}

$violations = New-Object System.Collections.Generic.List[string]
$testFiles = Get-ChildItem -Path $Root -Recurse -Filter *.java -File |
    Where-Object { $_.FullName -like '*src\test\java*' -or $_.FullName -like '*src/test/java*' }

foreach ($file in $testFiles) {
    $relPath = $file.FullName.Substring($Root.Length).TrimStart('\', '/')
    $lines = Get-Content -LiteralPath $file.FullName -Encoding UTF8
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*class\s+\w+Test\b') {
            # 跳过 public / protected / private 修饰
            if ($line -match '^\s*(public|protected|private|abstract|final)\s+class\b') { continue }
            $key = "$relPath`:$($i+1)"
            if ($allowSet.ContainsKey($relPath) -or $allowSet.ContainsKey($key)) { continue }
            $violations.Add("$key`: $($line.Trim())")
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "[STY-J002] 测试类非 public，共 $($violations.Count) 处：" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "  $_" }
    if (-not $DryRun) { exit 1 }
} else {
    Write-Host "[STY-J002] OK：所有测试类均为 public class。"
    exit 0
}
