$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne 'Win32NT') {
    throw 'Run this script on Windows with a 64-bit JDK 21 and WiX 3.'
}

$project = Split-Path -Parent $PSScriptRoot
$inputDir = Join-Path $project 'target\windows-package\app'
$installerDir = Join-Path $project 'target\windows-package\installer'
$jpackage = (Get-Command jpackage.exe).Source

if ($env:WIX) {
    $wixBin = Join-Path $env:WIX 'bin'
    $env:Path = "$wixBin;$env:Path"
}
Get-Command candle.exe, light.exe | Out-Null

Push-Location $project
try {
    [xml]$pom = Get-Content -Raw 'pom.xml'
    $jar = "$($pom.project.artifactId)-$($pom.project.version).jar"
    $version = $pom.project.version -replace '-.*$', ''
    $javafx = $pom.project.properties.'javafx.version'

    & .\mvnw.cmd -B -ntp clean verify org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies `
        '-DincludeScope=runtime' "-DoutputDirectory=$inputDir"
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven build failed.'
    }
    if (-not (Test-Path (Join-Path $inputDir "javafx-graphics-${javafx}-win.jar"))) {
        throw 'Windows x64 JavaFX libraries are missing. Run Maven with an x64 JDK 21.'
    }
    Copy-Item (Join-Path 'target' $jar) $inputDir

    & $jpackage --type msi --name ShouGongJi --app-version $version `
        --input $inputDir --dest $installerDir `
        --main-jar $jar --main-class com.sg.shougongji.ShouGongJiApplication `
        --add-modules 'java.desktop,java.net.http,java.sql,java.naming,java.management,jdk.unsupported,jdk.crypto.ec' `
        --win-per-user-install --win-menu --win-shortcut --win-upgrade-uuid F92E98C6-19F8-44CB-B8F8-A9DAD0CA3B50
    if ($LASTEXITCODE -ne 0) {
        throw 'Windows installer build failed.'
    }

    Get-ChildItem -Path $installerDir -Filter '*.msi' | Select-Object -ExpandProperty FullName
} finally {
    Pop-Location
}
