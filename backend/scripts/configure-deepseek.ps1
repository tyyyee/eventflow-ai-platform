#requires -Version 7.2
param([string]$Model='deepseek-flash',[switch]$Check)
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$directory=Join-Path $project '.local/local-life'
$file=Join-Path $directory 'deepseek.env'
if($Check){
    $configured=(Test-Path -LiteralPath $file) -and ((Get-Content -LiteralPath $file) -match '^DEEPSEEK_API_KEY=\S{10,}$').Count -gt 0
    Write-Output "DeepSeek local configuration present: $configured"
    exit
}
if($Model -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$'){throw 'Invalid model name'}
$secure=Read-Host 'Enter DeepSeek API key (hidden; saved only in ignored local configuration)' -AsSecureString
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try{
    $key=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if($key -notmatch '^[A-Za-z0-9_-]{10,200}$'){throw 'Invalid API key format'}
    New-Item -ItemType Directory -Force -Path $directory|Out-Null
    [IO.File]::WriteAllText($file,"DEEPSEEK_API_KEY=$key`nDEEPSEEK_BASE_URL=https://api.deepseek.com`nASSISTANT_MODEL=$Model`n",[Text.UTF8Encoding]::new($false))
}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$key=$null;$secure.Dispose()}
Write-Output 'Saved locally. Restart local-life backend to enable DeepSeek automatically. Do not commit or share deepseek.env.'
