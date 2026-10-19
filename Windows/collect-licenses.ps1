param([string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$assets = Get-Content Windows/SettlementMail.App/obj/project.assets.json -Raw | ConvertFrom-Json -AsHashtable
$root = @($assets.packageFolders.Keys)[0]
$notes = @('# Dependency notices','Packages below are bundled dependencies. Original metadata and included license files are copied beside this notice.','')
foreach ($pair in $assets.libraries.GetEnumerator()) {
  if ($pair.Value.type -ne 'package') { continue }
  $parts = $pair.Key.Split('/')
  $folder = Join-Path $root ($parts[0].ToLowerInvariant() + '/' + $parts[1])
  $target = Join-Path $Destination ($parts[0] + '-' + $parts[1])
  New-Item -ItemType Directory -Force $target | Out-Null
  Get-ChildItem $folder -File -Recurse | Where-Object { $_.Name -match '(?i)(license|copying|notice)' -or $_.Extension -eq '.nuspec' } | ForEach-Object {
    $relative = [System.IO.Path]::GetRelativePath($folder, $_.FullName)
    $output = Join-Path $target $relative
    New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($output)) | Out-Null
    Copy-Item $_.FullName $output
  }
  $notes += ('- ' + $pair.Key + ' https://www.nuget.org/packages/' + $pair.Key)
}
Invoke-WebRequest https://licenses.nuget.org/MIT -OutFile (Join-Path $Destination 'MIT.txt')
$notes | Set-Content -Encoding utf8 (Join-Path $Destination 'DEPENDENCIES.md')
