<#
.SYNOPSIS
  校验 Java 源码中是否使用 inline 限定符（pkg.Class），命中 AGENTS.md STY-J001。
兼容 PowerShell 5.1+ / PowerShell 7+。

.DESCRIPTION
  扫所有 *.java，对每个 .java：
    1. 解析 import 段（package 行之后到第一个非 import / 非空行前）。
    2. 在 import 段之外的代码体内，凡是匹配正则 \b[a-zA-Z_][\w]*\.[A-Z][\w]*\b 且其后
       标识符未在 import 段出现过的，视为违规。
    3. 输出 "path:line: 违规片段"，并返回退出码 1（除非 -DryRun）。

.PARAMETER Root
  仓库根路径，默认当前 cwd。

.PARAMETER DryRun
  仅输出，不返回非零退出码。

.PARAMETER Allow
  白名单，格式 path:line 或 path，多个用逗号分隔。

.EXAMPLE
  pwsh -File scripts/lint-imports.ps1
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

# 在 import 段中出现的简单类名集合
function Get-ImportSimpleNames {
    param([string[]]$Lines)
    $names = @{}
    foreach ($line in $Lines) {
        $trim = $line.Trim()
        if ($trim -match '^import\s+(static\s+)?([\w\.\*]+)\s*;') {
            $fqn = $matches[2]
            if ($fqn -like 'java.*' -or $fqn -like 'javax.*' -or $fqn -like 'org.*' -or $fqn -like 'com.*' -or $fqn -like 'lombok.*') {
                $simple = ($fqn.Split('.'))[-1]
                $names[$simple] = $true
            }
        }
    }
    return $names
}

$violations = New-Object System.Collections.Generic.List[string]
$javaFiles = Get-ChildItem -Path $Root -Recurse -Filter *.java -File

foreach ($file in $javaFiles) {
    $relPath = $file.FullName.Substring($Root.Length).TrimStart('\', '/')
    $lines = Get-Content -LiteralPath $file.FullName -Encoding UTF8

    # 找 import 段起止
    $importStart = -1; $importEnd = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Trim() -match '^import\s+') {
            if ($importStart -eq -1) { $importStart = $i }
            $importEnd = $i
        }
    }
    $importNames = Get-ImportSimpleNames ($lines[$importStart..$importEnd])

    # 在 import 段外匹配 inline 限定符
    # 简化正则：包名段（小写 / org / com / java / javax）+ 点 + 类名首字母大写
    # 排除行内注释以 // 开头的整行
    $pattern = '(?-i)\b(?:[a-z]+|org|com|java|javax|lombok)\.[a-z][\w]*(?:\.[a-z][\w]*)*\.[A-Z][\w]*'
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($importStart -ge 0 -and $i -ge $importStart -and $i -le $importEnd) { continue }
        $line = $lines[$i]
        $trim = $line.TrimStart()
        if ($trim.StartsWith('//')) { continue }
        if ($trim.StartsWith('/*') -or $trim.StartsWith('*')) { continue }
        if ($line -match $pattern) {
            $key = "$relPath`:$($i+1)"
            if ($allowSet.ContainsKey($relPath) -or $allowSet.ContainsKey($key)) { continue }
            $violations.Add("$key`: $($line.Trim())")
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "[STY-J001] inline 限定符违规，共 $($violations.Count) 处：" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "  $_" }
    if (-not $DryRun) { exit 1 }
} else {
    Write-Host "[STY-J001] OK：未发现 inline 限定符。"
    exit 0
}
