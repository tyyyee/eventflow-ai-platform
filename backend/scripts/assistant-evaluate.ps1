#requires -Version 7.2
param(
    [ValidateSet('deterministic','live','holdout')][string]$Mode='deterministic',
    [string]$Cases='',
    [string]$Label=($Mode+'-'+[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()),
    [string]$JavaHome='F:\Java17\JDK 17'
)
$ErrorActionPreference='Stop'
if($Label -notmatch '^[a-zA-Z0-9_-]{1,80}$'){throw 'Invalid report label'}
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$evidence=Join-Path $project 'docs/verification/2026-11-09-assistant-evaluation'
if(Test-Path -LiteralPath (Join-Path $evidence "$Label.json")){throw 'Existing report is preserved; choose a new label'}
$state=Join-Path $project '.local/assistant'
New-Item -ItemType Directory -Force -Path $state | Out-Null
$env:JAVA_HOME=$JavaHome;$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'
$env:ASSISTANT_EVAL_LABEL=$Label;$env:ASSISTANT_EVAL_IDS=$Cases
$balanceFile=Join-Path $evidence "$Label-balance.json"
$balanceRecord=[ordered]@{currency='CNY';before=$null;after=$null;note='Account balance observation, not per-request billing.'}
function Balance {
    try {
        $result=Invoke-RestMethod -Uri 'https://api.deepseek.com/user/balance' -Headers @{Authorization=('Bearer '+$env:DEEPSEEK_API_KEY)} -TimeoutSec 15
        if(!$result.is_available){throw 'Unavailable account'}
        $cny=@($result.balance_infos | Where-Object currency -eq 'CNY')
        if($cny.Count -ne 1){throw 'Missing CNY balance'}
        return [decimal]::Parse($cny[0].total_balance,[Globalization.CultureInfo]::InvariantCulture)
    } catch {throw 'Official balance check failed; key and provider response are not logged.'}
}
if($Mode -in 'live','holdout'){
    if([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)){throw 'Set DEEPSEEK_API_KEY in this shell first; never put it in source.'}
    if($Cases -and $Cases -notmatch '^[AB](0[1-9]|10)(,[AB](0[1-9]|10))*$'){throw 'Cases must be comma-separated A01..A10 or B01..B10'}
    $balanceRecord.before=Balance
    if($balanceRecord.before -lt 3){throw 'This batch requires at least 3 CNY remaining; refusing automatic paid calls.'}
    $env:ASSISTANT_MODEL='deepseek-flash'
    if($Mode -eq 'live'){$env:ASSISTANT_LIVE_EVAL='true';Remove-Item Env:ASSISTANT_LIVE_HOLDOUT -ErrorAction SilentlyContinue;$test='AssistantLiveEvaluationTest'}
    else{if($Cases){throw 'Holdout runs its fixed eight cases'};$env:ASSISTANT_LIVE_HOLDOUT='true';Remove-Item Env:ASSISTANT_LIVE_EVAL -ErrorAction SilentlyContinue;$test='AssistantHeldOutEvaluationTest'}
}else{
    Remove-Item Env:ASSISTANT_LIVE_EVAL -ErrorAction SilentlyContinue
    Remove-Item Env:ASSISTANT_LIVE_HOLDOUT -ErrorAction SilentlyContinue
    if($Cases){throw 'Case selection is supported only for live mode'}
    $test='AssistantEvaluationTest,AssistantBusinessTest,AssistantHttpTest,DeepSeekAssistantModelTest'
}
$maven=Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists/apache-maven-3.9.9') -Recurse -Filter mvn.cmd | Select-Object -First 1 -ExpandProperty FullName
if(!$maven){throw 'Maven 3.9.9 is required'}
$log=Join-Path $state "$Label.log"
Push-Location (Join-Path $project 'backend')
try {
    & $maven ('-Dmaven.repo.local='+(Join-Path $env:USERPROFILE '.m2/repository')) -s (Join-Path $PSScriptRoot 'assistant-maven-settings.xml') -o -Psharding "-Dtest=$test" test *> $log
    $resultCode=$LASTEXITCODE
    Get-Content -LiteralPath $log -Tail 15
}finally{
    Pop-Location
    if($Mode -in 'live','holdout'){
        try{$balanceRecord.after=Balance}catch{$balanceRecord.after='Unavailable; do not interpret as zero cost'}
        New-Item -ItemType Directory -Force -Path $evidence | Out-Null
        $balanceRecord | ConvertTo-Json | Set-Content -LiteralPath $balanceFile -Encoding utf8
    }
    Remove-Item Env:ASSISTANT_LIVE_EVAL -ErrorAction SilentlyContinue
    Remove-Item Env:ASSISTANT_LIVE_HOLDOUT -ErrorAction SilentlyContinue
}
if(Test-Path -LiteralPath (Join-Path $evidence "$Label.json")){
    $report=Get-Content -LiteralPath (Join-Path $evidence "$Label.json") -Raw | ConvertFrom-Json
    $report | Select-Object mode,casesRecorded,passed,failed,notRunBudget,providerCalls,reportedTotalTokens,usageUnknown,conservativeReportedTokenCostYuan | ConvertTo-Json
}
exit $resultCode
