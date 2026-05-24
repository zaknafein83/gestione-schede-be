# Script di attivazione dell'ambiente di sviluppo per la sessione PowerShell corrente.
# Imposta JAVA_HOME e aggiunge Maven (bundled con IntelliJ) al PATH SOLO nella sessione.
# Non modifica nulla a livello utente/sistema — chiude la shell e l'effetto svanisce.
#
# Uso:
#   . .\activate-env.ps1
# (nota il punto iniziale: "dot sourcing", serve perché le modifiche restino nella shell)

$IntelliJDir = "C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1"
$JdkDir      = Join-Path $IntelliJDir "jbr"
$MavenDir    = Join-Path $IntelliJDir "plugins\maven\lib\maven3"
$FlutterDir  = "C:\Program Files\flutter"

if (-not (Test-Path $JdkDir)) {
    Write-Error "JDK non trovato in $JdkDir — controlla il path di IntelliJ"
    return
}
if (-not (Test-Path $MavenDir)) {
    Write-Error "Maven non trovato in $MavenDir — controlla il path di IntelliJ"
    return
}

$env:JAVA_HOME = $JdkDir
$env:Path      = "$JdkDir\bin;$MavenDir\bin;$FlutterDir\bin;" + $env:Path

Write-Host "Ambiente attivato per questa sessione:" -ForegroundColor Green
Write-Host "  JAVA_HOME = $env:JAVA_HOME"
Write-Host "  Maven     = $MavenDir"
Write-Host "  Flutter   = $FlutterDir"
Write-Host ""
Write-Host "Per verificare: java -version | mvn -version | flutter --version"
