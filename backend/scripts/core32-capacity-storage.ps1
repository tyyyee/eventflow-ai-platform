#requires -Version 7.2
param([string]$MysqlHome='F:\software\Mysql\mysql-8.0.31-winx64',[string]$JavaHome='F:\Java17\JDK 17')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/core32-capacity';$evidence=Join-Path $project 'docs/verification/2026-10-27-core32-capacity'
$stamp=Get-Date -Format yyyyMMddHHmmss;$backup=Join-Path $state "backup-$stamp";New-Item -ItemType Directory -Force $backup | Out-Null
$mysql=Join-Path $MysqlHome 'bin/mysql.exe';$dump=Join-Path $MysqlHome 'bin/mysqldump.exe'
function Sql($folder,$statement){Push-Location $folder;try{$value=$statement | & $mysql --defaults-extra-file=root.cnf --batch --skip-column-names --default-character-set=utf8mb4 2>&1;if($LASTEXITCODE -ne 0){throw 'Capacity storage SQL failed.'};return $value}finally{Pop-Location}}
$pidFile=Join-Path $project '.local/core32/backend.pid';$wasRunning=$false
if(Test-Path $pidFile){$ownedPid=[int](Get-Content $pidFile);$wasRunning=$null -ne (Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue)}
$result=@{startedAt=(Get-Date).ToString('o');scope='Primary order tables only, real logical export/restore with full field comparisons; no full-business recovery or replica failover claim';units=@()}
try {
    if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') halt}
    $env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8';$env:CORE32_CAPACITY='true'
    $env:CORE32_PASSWORD=(Get-Content (Join-Path $project '.local/sharding/credentials.json') -Raw | ConvertFrom-Json).password
    $maven=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
    Push-Location (Join-Path $project 'backend');try{& $maven -Psharding '-Dtest=CoreConnectionCapacityTest' test > core32-connections.log 2>&1;if($LASTEXITCODE -ne 0){Get-Content core32-connections.log -Tail 16;throw 'Connection admission test failed.'}}finally{Pop-Location}
    foreach($mode in @('single','one-of-four')){
        $folder=if($mode -eq 'single'){Join-Path $project '.local/sharding/source'}else{Join-Path $project '.local/core32/db0'}
        [string[]]$tables=if($mode -eq 'single'){@('cx_order')}else{@(0..7 | ForEach-Object {"cx_order_$_"})}
        $restore="qupiao_cap_restore_$($mode.Replace('-','_'))_$stamp";$output=Join-Path $backup "$mode.sql"
        $rows=0L;foreach($table in $tables){$rows += [long](Sql $folder "SELECT COUNT(*) FROM qupiao_core_capacity.$table;")}
        if($rows -lt $(if($mode -eq 'single'){1000000}else{250000})){throw 'Million-order benchmark must complete first.'}
        $clock=[Diagnostics.Stopwatch]::StartNew();Push-Location $folder
        try{& $dump --defaults-extra-file=root.cnf --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-comments --default-character-set=utf8mb4 "--result-file=$output" qupiao_core_capacity @tables;if($LASTEXITCODE -ne 0){throw 'Export failed'}}finally{Pop-Location};$clock.Stop();$exportSeconds=$clock.Elapsed.TotalSeconds
        Sql $folder "CREATE DATABASE $restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" | Out-Null
        $clock.Restart();$info=[Diagnostics.ProcessStartInfo]::new();$info.FileName=$mysql;$info.WorkingDirectory=$folder;$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardError=$true;$info.ArgumentList.Add('--defaults-extra-file=root.cnf');$info.ArgumentList.Add('--default-character-set=utf8mb4');$info.ArgumentList.Add($restore)
        $child=[Diagnostics.Process]::Start($info);$stderr=$child.StandardError.ReadToEndAsync();$inputFile=[IO.File]::OpenRead($output)
        try{$inputFile.CopyTo($child.StandardInput.BaseStream);$child.StandardInput.Close();$child.WaitForExit();if($child.ExitCode -ne 0){throw 'Restore failed; inspect local dump.'};$stderr.GetAwaiter().GetResult() | Out-Null}finally{$inputFile.Dispose();$child.Dispose()};$clock.Stop();$restoreSeconds=$clock.Elapsed.TotalSeconds
        $verified=0L;foreach($table in $tables){$original=[long](Sql $folder "SELECT COUNT(*) FROM qupiao_core_capacity.$table;");$actual=[long](Sql $folder "SELECT COUNT(*) FROM $restore.$table;");if($original -ne $actual){throw 'Restored count mismatch'}
            $different=[long](Sql $folder "SELECT COUNT(*) FROM qupiao_core_capacity.$table a LEFT JOIN $restore.$table b ON a.id=b.id WHERE b.id IS NULL OR NOT(a.user_id<=>b.user_id AND a.request_id<=>b.request_id AND a.activity_id<=>b.activity_id AND a.price_cents<=>b.price_cents AND a.state<=>b.state AND a.confirm_until<=>b.confirm_until AND a.created_at<=>b.created_at);")
            if($different -ne 0){throw 'Restored field mismatch'};$verified+=$actual
        }
        $unit=@{mode=$mode;port=if($mode -eq 'single'){33307}else{33400};tables=$tables;rows=$rows;exportBytes=(Get-Item $output).Length;sha256=(Get-FileHash $output -Algorithm SHA256).Hash;exportSeconds=$exportSeconds;restoreSeconds=$restoreSeconds;restoredSchema=$restore;verifiedRows=$verified;differentRows=0}
        $result.units+=,$unit;$result | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $evidence 'storage-recovery.json') -Encoding utf8
        Write-Output "$mode export/restore verified: $verified rows."
    }
    $result.completedAt=(Get-Date).ToString('o');$result | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $evidence 'storage-recovery.json') -Encoding utf8
} finally {if($wasRunning){& (Join-Path $PSScriptRoot 'core32-local.ps1') run}}
