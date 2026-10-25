$content = [System.IO.File]::ReadAllText('deploy/setup_db.sh')
$content = $content.Replace("`r`n", "`n")
[System.IO.File]::WriteAllText('deploy/setup_db.sh', $content)
