param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $root '.work/tools'
$maven = Join-Path $tools 'apache-maven-3.9.9/bin/mvn.cmd'
if (!(Test-Path -LiteralPath $maven)) {
  New-Item -ItemType Directory -Force -Path $tools | Out-Null
  $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
  $zip = Join-Path $tools 'maven-3.9.9.zip'
  Invoke-WebRequest -UseBasicParsing $url -OutFile $zip
  $expected = (Invoke-WebRequest -UseBasicParsing ($url + '.sha512')).Content.Trim().Split(' ')[0]
  if ((Get-FileHash -LiteralPath $zip -Algorithm SHA512).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) { throw 'Maven checksum mismatch' }
  Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
}
& $maven -f (Join-Path $root 'backend/pom.xml') @MavenArgs
exit $LASTEXITCODE
