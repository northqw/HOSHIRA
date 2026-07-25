param(
    [string]$Version = "0.2.6"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$desktopApp = Join-Path $repoRoot "desktopApp"
$wixSource = Join-Path $repoRoot "build\wix311"
$wixProxy = Join-Path $repoRoot "build\wix311-proxy"
$resourceDir = Join-Path $desktopApp "src\main\package\windows"
$iconFile = Join-Path $desktopApp "src\main\resources\icons\hoshira.ico"
$appImage = Join-Path $desktopApp "build\compose\binaries\main-release\app\Hoshira"
$outputDir = Join-Path $repoRoot "build-artifacts"
$outputFile = Join-Path $outputDir "Hoshira-$Version.exe"
$csharpCompiler = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"

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

    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    if (Test-Path -LiteralPath $outputFile) {
        Remove-Item -LiteralPath $outputFile -Force
    }

    $previousPath = $env:Path
    $env:Path = "$wixProxy;$previousPath"
    try {
        & $jpackage `
            --type exe `
            --app-image $appImage `
            --dest $outputDir `
            --name Hoshira `
            --app-version $Version `
            --icon $iconFile `
            --vendor "Hoshira Community" `
            --description "Hoshira desktop application" `
            --win-dir-chooser `
            --win-shortcut `
            --win-menu `
            --win-menu-group "Hoshira" `
            --win-upgrade-uuid "6a7125ad-4baa-4e21-b9a1-32a664ccf60c" `
            --resource-dir $resourceDir
        if ($LASTEXITCODE -ne 0) {
            throw "jpackage failed to build the Windows installer."
        }
    }
    finally {
        $env:Path = $previousPath
    }

    Write-Host "Installer created: $outputFile"
}
finally {
    Pop-Location
}
