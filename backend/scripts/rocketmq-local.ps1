#requires -Version 7.2
param([ValidateSet('start','stop','status')][string]$Action='status',[string]$JavaHome='F:\Java17\JDK 17')
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$state=Join-Path $project '.local/rocketmq'
$distribution=Join-Path $state 'distribution/rocketmq-all-5.3.2-bin-release'
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($project))).Substring(0,12).ToLowerInvariant()
$runtime=Join-Path ([IO.Path]::GetTempPath()) "qupiao-rocketmq-$hash"
function Port([int]$number){$probe=[Net.Sockets.TcpClient]::new();try{$probe.Connect('127.0.0.1',$number);return $true}catch{return $false}finally{$probe.Dispose()}}
function Halt($name){
    $file=Join-Path $state "$name.pid"
    if(Test-Path $file){$ownedPid=[int](Get-Content $file);$process=Get-CimInstance Win32_Process -Filter "ProcessId=$ownedPid" -ErrorAction SilentlyContinue
        if($process){if(!$process.CommandLine.Contains((Join-Path $state "$name.conf"))){throw "Saved $name PID belongs to another process"};Stop-Process -Id $ownedPid}
    }
}
function Launch($name,$main,$heap,$port){
    $config=Join-Path $state "$name.conf"
    if(Port $port){$file=Join-Path $state "$name.pid";if(!(Test-Path $file)){throw "Unowned listener on port $port"};$process=Get-CimInstance Win32_Process -Filter "ProcessId=$([int](Get-Content $file))" -ErrorAction SilentlyContinue;if(!$process -or !$process.CommandLine.Contains($config)){throw "Unowned listener on port $port"};return}
    $process=Start-Process (Join-Path $JavaHome 'bin/java.exe') -ArgumentList @('-Xms64m',"-Xmx${heap}m",'-XX:MaxDirectMemorySize=256m','-Dfile.encoding=UTF-8',('-Drocketmq.home.dir="'+$distribution+'"'),'-cp',('"'+(Join-Path $distribution 'lib/*')+'"'),$main,'-c',('"'+$config+'"')) -WorkingDirectory $state -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $state "$name.stdout.log") -RedirectStandardError (Join-Path $state "$name.stderr.log")
    [IO.File]::WriteAllText((Join-Path $state "$name.pid"),[string]$process.Id)
    for($poll=0;$poll -lt 100;$poll++){if(Port $port){return};if($process.HasExited){throw "$name exited; inspect .local/rocketmq/$name.stderr.log"};Start-Sleep -Milliseconds 300};throw "$name did not listen on port $port"
}
if($Action -eq 'stop'){Halt 'broker';Halt 'namesrv'}
if($Action -eq 'start'){
    if(!(Test-Path (Join-Path $distribution 'lib/rocketmq-broker-5.3.2.jar'))){throw 'Install and SHA512-verify the Apache 5.3.2 distribution in .local/rocketmq/distribution first; see docs/engineering/RocketMQ迁移与验收.md'}
    New-Item -ItemType Directory -Force $state,$runtime | Out-Null
    $env:ROCKETMQ_HOME=$distribution
    [IO.File]::WriteAllText((Join-Path $state 'namesrv.conf'),"listenPort=39876`nbindAddress=127.0.0.1`n")
    [IO.File]::WriteAllText((Join-Path $state 'broker.conf'),@"
brokerClusterName=qupiao-local
brokerName=qupiao-broker
brokerId=0
namesrvAddr=127.0.0.1:39876
brokerIP1=127.0.0.1
brokerIP2=127.0.0.1
bindAddress=127.0.0.1
listenPort=39911
haListenPort=39912
storePathRootDir=$($runtime.Replace('\','/'))
brokerRole=ASYNC_MASTER
flushDiskType=SYNC_FLUSH
autoCreateTopicEnable=true
timerWheelEnable=true
timerMaxDelaySec=86400
mappedFileSizeCommitLog=67108864
mappedFileSizeConsumeQueue=600000
fileReservedTime=24
deleteWhen=04
"@)
    Launch 'namesrv' 'org.apache.rocketmq.namesrv.NamesrvStartup' 128 39876
    Launch 'broker' 'org.apache.rocketmq.broker.BrokerStartup' 512 39911
}
foreach($pair in @(@('namesrv',39876),@('broker',39911))){Write-Output "$($pair[0]): $(if(Port $pair[1]){'listening'}else{'stopped'})"}
