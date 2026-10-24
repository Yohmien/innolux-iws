<#
.SYNOPSIS
创建并初始化一个空的 IWS+ MySQL 8.0 数据库。

.DESCRIPTION
MySQL 8.0 不允许在 PREPARE 中执行 USE，故由本入口完成目标库创建和连接选择。
目标库已存在时，只要包含任何表、视图、存储程序、触发器或事件即停止。
数据库初始化内容全部来自同目录的 iws_init_from_empty.sql。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Database,

    [string]$Server = '127.0.0.1',

    [ValidateRange(1, 65535)]
    [int]$Port = 3306,

    [string]$User = 'root',

    [PSCredential]$Credential,

    [string]$Mysql = 'mysql.exe',

    [ValidateSet('DISABLED', 'PREFERRED', 'REQUIRED', 'VERIFY_CA', 'VERIFY_IDENTITY')]
    [string]$SslMode = 'PREFERRED',

    [string]$InitSql = (Join-Path $PSScriptRoot 'iws_init_from_empty.sql')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Database)) {
    throw '目标库名不能为空。'
}
if ($Database.Length -gt 64) {
    throw '目标库名不能超过 64 个字符。'
}
if ($Database.Contains([char]0) -or $Database -ne $Database.TrimEnd()) {
    throw '目标库名包含 NUL 或以空格结尾，MySQL 不接受该 schema 名。'
}
if (-not (Test-Path -LiteralPath $InitSql -PathType Leaf)) {
    throw "初始化 SQL 不存在：$InitSql"
}

$mysqlPath = (Get-Command $Mysql -ErrorAction Stop).Source
$databaseIdentifier = '`' + $Database.Replace('`', '``') + '`'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$mysqlBaseArgs = @(
    '--no-defaults',
    '--protocol=TCP',
    "--host=$Server",
    "--port=$Port",
    "--user=$User",
    "--ssl-mode=$SslMode",
    '--default-character-set=utf8mb4',
    '--batch',
    '--raw',
    '--skip-column-names'
)

$previousMysqlPwd = $env:MYSQL_PWD
try {
    if ($null -ne $Credential) {
        $env:MYSQL_PWD = $Credential.GetNetworkCredential().Password
    }

    function Invoke-Mysql {
        param(
            [string[]]$Arguments,
            [string]$InputText,
            [switch]$FromStdin
        )

        if ($FromStdin) {
            $output = $InputText | & $mysqlPath @mysqlBaseArgs @Arguments 2>&1
        }
        else {
            $output = & $mysqlPath @mysqlBaseArgs @Arguments 2>&1
        }
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            $detail = ($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
            throw "mysql 执行失败，退出码 $exitCode。$([Environment]::NewLine)$detail"
        }
        return @($output | ForEach-Object { $_.ToString() })
    }

    $schemas = Invoke-Mysql -Arguments @(
        '--execute=SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME'
    )
    $schemaExists = $schemas -ccontains $Database

    if ($schemaExists) {
        $state = Invoke-Mysql -Arguments @(
            "--database=$Database",
            "--execute=SELECT 'tables', COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() UNION ALL SELECT 'routines', COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE() UNION ALL SELECT 'triggers', COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() UNION ALL SELECT 'events', COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = DATABASE()"
        )
        $occupied = @($state | Where-Object {
            $parts = $_ -split "`t", 2
            $parts.Count -eq 2 -and [int64]$parts[1] -gt 0
        })
        if ($occupied.Count -gt 0) {
            throw "目标库 $Database 已包含对象，拒绝修改现有 schema：$($occupied -join '; ')"
        }
        Write-Host "目标库 $Database 已存在且为空，继续初始化。"
    }
    else {
        Invoke-Mysql -Arguments @(
            "--execute=CREATE DATABASE $databaseIdentifier CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
        ) | Out-Null
        Write-Host "已创建目标库 $Database。"
    }

    $sqlText = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $InitSql).Path, $utf8)
    Invoke-Mysql -Arguments @("--database=$Database") -InputText $sqlText -FromStdin | ForEach-Object {
        Write-Output $_
    }
    Write-Host "目标库 $Database 全量初始化完成。"
}
finally {
    if ($null -eq $previousMysqlPwd) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    else {
        $env:MYSQL_PWD = $previousMysqlPwd
    }
}
