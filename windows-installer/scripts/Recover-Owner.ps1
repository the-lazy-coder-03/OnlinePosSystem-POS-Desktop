$ErrorActionPreference='Stop'
try {
 Write-Host 'OnlinePOS - owner PIN recovery'
 Write-Host 'Requires the installation administrator and separately enrolled developer PIN.'
 Write-Host 'Recovery attempts are recorded in the audit history. No previous PIN is revealed.'
 & (Join-Path $PSScriptRoot 'Local-Database.ps1') -Operation RecoverOwner
 Write-Host 'Owner PIN reset. Sign in again on each terminal using the new PIN.'
 [void](Read-Host 'Press Enter to close')
 exit 0
} catch {
 Write-Host 'Recovery refused. Check credentials, enrolment status and cooldown, then retry.'
 [void](Read-Host 'Press Enter to close')
 exit 1
}
