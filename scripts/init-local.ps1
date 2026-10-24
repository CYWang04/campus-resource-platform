param()
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root '.env'
if (Test-Path -LiteralPath $target) { Write-Host 'Local configuration already exists; preserved.'; exit 0 }
function New-Secret {
  $bytes = New-Object byte[] 24
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
$settings = @('MYSQL_ROOT_PASSWORD','M1_DB_PASSWORD','M2_DB_PASSWORD','M3_DB_PASSWORD','SERVICE_KEY') | ForEach-Object { $_ + '=' + (New-Secret) }
$settings += 'DEMO_PASSWORD=CampusDemo2026!'
[IO.File]::WriteAllLines($target, $settings, (New-Object Text.UTF8Encoding $false))
Write-Host 'Created ignored .env. Demo accounts use the public password documented in docs/ACCEPTANCE.md.'
