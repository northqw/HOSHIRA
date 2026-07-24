param(
    [string]$Version = "0.2.4"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$desktopApp = Join-Path $repoRoot "desktopApp"
$wixSource = Join-Path $repoRoot "build\wix311"
$wixProxy = Join-Path $repoRoot "build\wix311-custom-proxy"
$resourceDir = Join-Path $desktopApp "src\main\package\windows"
$iconFile = Join-Path $desktopApp "src\main\resources\icons\hoshira.ico"
$appImage = Join-Path $desktopApp "build\compose\binaries\main-release\app\Hoshira"
$payloadDir = Join-Path $desktopApp "build\custom-installer\payload"
$tempDir = Join-Path $desktopApp "build\custom-installer\jpackage-temp-$Version-$PID"
$outputDir = Join-Path $repoRoot "build-artifacts"
$outputFile = Join-Path $outputDir "Hoshira-$Version-Setup.exe"
$installerSource = Join-Path $desktopApp "installer\HoshiraInstaller.cs"
$csharpCompiler = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"

if ($Version -ne "0.2.4") {
    throw "Update InstallerVersion and assembly version in HoshiraInstaller.cs before building $Version."
}
if (-not (Test-Path -LiteralPath $csharpCompiler)) {
    throw "The .NET Framework C# compiler was not found."
}
if (-not (Test-Path -LiteralPath $jpackage)) {
    throw "Set JAVA_HOME to JDK 21 before building the installer."
}

Push-Location $repoRoot
try {
    & ".\gradlew.bat" ":desktopApp:createReleaseDistributable"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to build the Hoshira application image."
    }
    if (-not (Test-Path -LiteralPath $wixSource)) {
        throw "WiX 3.11 was not provisioned at $wixSource"
    }

    New-Item -ItemType Directory -Path $wixProxy -Force | Out-Null
    Copy-Item -Path (Join-Path $wixSource "*") -Destination $wixProxy -Recurse -Force
    Move-Item -LiteralPath (Join-Path $wixProxy "light.exe") `
        -Destination (Join-Path $wixProxy "light-real.exe") -Force

    & $csharpCompiler /nologo /optimize+ `
        "/out:$(Join-Path $wixProxy 'light.exe')" `
        (Join-Path $PSScriptRoot "WixLightProxy.cs")
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to compile the WiX linker proxy."
    }

    New-Item -ItemType Directory -Path $payloadDir -Force | Out-Null
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

    $payloadFile = Join-Path $payloadDir "Hoshira-$Version.msi"
    if (Test-Path -LiteralPath $payloadFile) {
        Remove-Item -LiteralPath $payloadFile -Force
    }
    if (Test-Path -LiteralPath $outputFile) {
        Remove-Item -LiteralPath $outputFile -Force
    }

    $previousPath = $env:Path
    $env:Path = "$wixProxy;$previousPath"
    try {
        & $jpackage `
            --type msi `
            --app-image $appImage `
            --dest $payloadDir `
            --temp $tempDir `
            --name Hoshira `
            --app-version $Version `
            --icon $iconFile `
            --vendor "Hoshira Community" `
            --description "Hoshira desktop application" `
            --win-shortcut `
            --win-menu `
            --win-menu-group "Hoshira" `
            --win-upgrade-uuid "6a7125ad-4baa-4e21-b9a1-32a664ccf60c" `
            --resource-dir $resourceDir
        if ($LASTEXITCODE -ne 0) {
            throw "jpackage failed to build the MSI payload."
        }
    }
    finally {
        $env:Path = $previousPath
    }

    if (-not (Test-Path -LiteralPath $payloadFile)) {
        throw "MSI payload was not created at $payloadFile"
    }

    & $csharpCompiler `
        /nologo `
        /target:winexe `
        /platform:anycpu `
        /optimize+ `
        "/win32icon:$iconFile" `
        "/out:$outputFile" `
        /reference:System.dll `
        /reference:System.Core.dll `
        /reference:System.Drawing.dll `
        /reference:System.Windows.Forms.dll `
        "/resource:$payloadFile,Hoshira.Payload.msi" `
        "/resource:$iconFile,Hoshira.SetupIcon" `
        $installerSource
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to compile the custom Hoshira installer shell."
    }

    Write-Host "Custom installer created: $outputFile"
}
finally {
    Pop-Location
}
