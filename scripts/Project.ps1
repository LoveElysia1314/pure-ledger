param(
    [Parameter(Position=0)][ValidateSet('Doctor','Test','TestAndroid','Build','Check','Release')]
    [string]$Action='Doctor'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$configFile=Join-Path $projectRoot '.private/developer.json'
$config=if(Test-Path -LiteralPath $configFile){Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json}else{$null}
$previousJava=$env:JAVA_HOME
$previousAndroid=$env:ANDROID_HOME
$previousGradle=$env:GRADLE_USER_HOME
try {
    if($config.javaHome){$env:JAVA_HOME=$config.javaHome}
    if($config.androidHome){$env:ANDROID_HOME=$config.androidHome}
    if($config.gradleUserHome){$env:GRADLE_USER_HOME=$config.gradleUserHome}
    if($Action -eq 'Doctor') {
        [pscustomobject]@{root=$projectRoot;javaHome=$env:JAVA_HOME;androidHome=$env:ANDROID_HOME;localConfig=(Test-Path -LiteralPath $configFile);apk=(Test-Path -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'))}|ConvertTo-Json
        exit 0
    }
    if($Action -eq 'Release') {
        Push-Location -LiteralPath $projectRoot
        try { & python (Join-Path $PSScriptRoot 'release.py') build; if($LASTEXITCODE -ne 0){throw 'Signed release build failed'} }
        finally { Pop-Location }
        return
    }
    [string[]]$tasks=switch($Action){
        'Test' {@('-PcoreOnly=true',':core:test')}
        'TestAndroid' {@(':app:testDebugUnitTest')}
        'Build' {@(':app:assembleDebug')}
        'Check' {@(':core:test',':app:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:assembleDebugAndroidTest')}
    }
    if($config.robolectricDir -and $Action -in @('TestAndroid','Check')){$tasks+=('-ProbolectricDependencyDir='+$config.robolectricDir)}
    $tasks+='--console=plain'
    Push-Location -LiteralPath $projectRoot
    try {& (Join-Path $projectRoot 'gradlew.bat') @tasks;$result=$LASTEXITCODE}finally{Pop-Location}
    if($result -ne 0){throw "Gradle exited with $result"}
} finally {
    $env:JAVA_HOME=$previousJava
    $env:ANDROID_HOME=$previousAndroid
    $env:GRADLE_USER_HOME=$previousGradle
}
