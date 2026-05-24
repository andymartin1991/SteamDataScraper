param(
    [Parameter(Mandatory = $false)]
    [string]$Message = "",

    [Parameter(Mandatory = $false)]
    [ValidateRange(1, 10240)]
    [int]$MaxSizeMB = 100,

    [Parameter(Mandatory = $false)]
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'

function Assert-GitRepo {
    git rev-parse --is-inside-work-tree *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "No parece que estés dentro de un repositorio Git. Ejecuta este script desde la raíz del repo."
    }
}

function Get-RepoRoot {
    $root = (git rev-parse --show-toplevel).Trim()
    if ([string]::IsNullOrWhiteSpace($root)) {
        throw "No se pudo determinar la raíz del repo (git rev-parse --show-toplevel)."
    }
    return $root
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    # Git suele devolver rutas con '/'. Normalizamos y resolvemos contra la raíz del repo.
    $p = $Path -replace '/', '\\'

    # Si ya es absoluta, úsala tal cual.
    if ([System.IO.Path]::IsPathRooted($p)) {
        return $p
    }

    # Resolver incluso si contiene '..'
    return [System.IO.Path]::GetFullPath((Join-Path -Path $RepoRoot -ChildPath $p))
}

function Get-FileSizeBytes {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [string]$RepoRoot
    )

    $abs = Resolve-RepoPath -Path $Path -RepoRoot $RepoRoot
    if (Test-Path -LiteralPath $abs) {
        return (Get-Item -LiteralPath $abs).Length
    }
    return $null
}

function Unstage-Path {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    # Preferimos restore (más moderno) y dejamos reset como fallback.
    # Usamos -- para evitar interpretación de paths como flags.
    try {
        git restore --staged -- $Path 2>$null
    } catch {
        # ignore
    }

    try {
        git reset -- $Path 2>$null
    } catch {
        # ignore
    }
}

function Assert-NoLargeFilesStaged {
    param(
        [long]$MaxBytes,
        [string]$RepoRoot
    )

    $staged = @(git diff --cached --name-only)
    $tooBig = @()
    foreach ($p in $staged) {
        $size = Get-FileSizeBytes -Path $p -RepoRoot $RepoRoot
        if ($size -ne $null -and $size -gt $MaxBytes) {
            $tooBig += $p
        }
    }

    if ($tooBig.Count -gt 0) {
        Write-Host "\nERROR: Quedan archivos en staging que superan el límite ($([math]::Round($MaxBytes/1MB,0))MB)." -ForegroundColor Red
        foreach ($p in $tooBig) {
            Write-Host "  - $p" -ForegroundColor Red
        }
        throw "Aborto para evitar un push rechazado por archivos grandes."
    }
}

function Assert-NoSensitiveTextStaged {
    $staged = @(git diff --cached --name-only --diff-filter=ACMR)
    $findings = @()
    $safePlaceholders = @(
        'tu_clave_steam',
        'clave_rawg_1',
        'clave_rawg_2',
        'pon_aqui_tu_clave_steam',
        'RUTA_LOCAL_AL_JDK_17'
    )

    foreach ($p in $staged) {
        $content = git show ":$p" 2>$null
        if ($LASTEXITCODE -ne 0 -or $null -eq $content) { continue }

        $text = ($content -join "`n")
        if ([string]::IsNullOrWhiteSpace($text)) { continue }

        $hasUnsafeSensitivePattern = $false
        foreach ($line in ($text -split "`r?`n")) {
            $hasSensitivePattern =
                $line -match '([A-Za-z]:\\Users\\|C:/Users/|/Users/|/home/)' -or
                $line -match '-----BEGIN (RSA |DSA |EC |OPENSSH |PGP )?PRIVATE KEY-----' -or
                $line -match '(?i)Authorization\s*[:=]\s*["'']?\s*(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{10,}' -or
                $line -match '(?i)(api[_-]?key|apikey|access[_-]?token|auth[_-]?token|client[_-]?secret|password|passwd|pwd)\s*[:=]\s*["'']?[A-Za-z0-9._~+/=-]{16,}'

            if (-not $hasSensitivePattern) { continue }

            $isPlaceholderLine = $false
            foreach ($placeholder in $safePlaceholders) {
                if ($line.Contains($placeholder)) {
                    $isPlaceholderLine = $true
                    break
                }
            }

            if (-not $isPlaceholderLine) {
                $hasUnsafeSensitivePattern = $true
                break
            }
        }

        if ($hasUnsafeSensitivePattern) {
            $findings += $p
        }
    }

    if ($findings.Count -gt 0) {
        Write-Host "\nERROR: Posibles rutas locales o secretos detectados en staging:" -ForegroundColor Red
        foreach ($p in ($findings | Select-Object -Unique)) {
            Write-Host "  - $p" -ForegroundColor Red
        }
        throw "Aborto para evitar subir información sensible al repositorio público."
    }
}

function Stage-FilterLargeFiles {
    param(
        [long]$MaxBytes,
        [string]$RepoRoot
    )

    Write-Host "\n==> Estado actual" -ForegroundColor Cyan
    git status

    Write-Host "\n==> Limpiando staging (git reset)" -ForegroundColor Cyan
    git reset

    Write-Host "\n==> Stage: cambios en archivos ya trackeados (git add -u)" -ForegroundColor Cyan
    git add -u

    Write-Host "\n==> Filtrando del staging archivos trackeados > $([math]::Round($MaxBytes/1MB,0))MB" -ForegroundColor Cyan
    $staged = @(git diff --cached --name-only)
    foreach ($p in $staged) {
        $size = Get-FileSizeBytes -Path $p -RepoRoot $RepoRoot
        if ($size -ne $null -and $size -gt $MaxBytes) {
            Unstage-Path -Path $p
            Write-Host "  - Saltado (>límite, trackeado): $p" -ForegroundColor Yellow
        }
    }

    Write-Host "\n==> Stage: archivos nuevos <= $([math]::Round($MaxBytes/1MB,0))MB" -ForegroundColor Cyan
    $untracked = @(git ls-files -o --exclude-standard)
    foreach ($p in $untracked) {
        $size = Get-FileSizeBytes -Path $p -RepoRoot $RepoRoot
        if ($size -eq $null) { continue }

        if ($size -le $MaxBytes) {
            git add -- $p
        } else {
            Write-Host "  - Saltado (>límite, nuevo): $p" -ForegroundColor Yellow
        }
    }

    # Guardia final: si queda alguno grande, intentamos una limpieza extra y solo entonces abortamos.
    $staged2 = @(git diff --cached --name-only)
    foreach ($p in $staged2) {
        $size = Get-FileSizeBytes -Path $p -RepoRoot $RepoRoot
        if ($size -ne $null -and $size -gt $MaxBytes) {
            Unstage-Path -Path $p
        }
    }

    Assert-NoLargeFilesStaged -MaxBytes $MaxBytes -RepoRoot $RepoRoot
}

function Ensure-CommitMessage {
    param([string]$Message)

    if (-not [string]::IsNullOrWhiteSpace($Message)) {
        return $Message
    }

    # Interactivo si no se pasa -Message
    $msg = Read-Host "Introduce el mensaje del commit"
    if ([string]::IsNullOrWhiteSpace($msg)) {
        throw 'Mensaje de commit vacío. Pasa -Message "..." o escribe uno cuando se te pida.'
    }
    return $msg
}

function Assert-ThereIsSomethingToCommit {
    $porcelain = git status --porcelain
    if ([string]::IsNullOrWhiteSpace($porcelain)) {
        Write-Host "\nNo hay cambios para commitear." -ForegroundColor Green
        exit 0
    }
}

function Do-CommitAndPush {
    param(
        [string]$Message,
        [switch]$NoPush
    )

    Write-Host "\n==> Resumen (staged)" -ForegroundColor Cyan
    git status

    $diffCached = git diff --cached --name-only
    if ([string]::IsNullOrWhiteSpace($diffCached)) {
        Write-Host "\nNo hay nada en staging después del filtro. No se crea commit." -ForegroundColor Yellow

        if ($NoPush) {
            Write-Host "\n(NoPush) Push omitido." -ForegroundColor Yellow
            exit 0
        }

        # Aun así puede haber commits locales pendientes de subir.
        Write-Host "\n==> Push (commits existentes)" -ForegroundColor Cyan
        git push
        return
    }

    Write-Host "\n==> Commit" -ForegroundColor Cyan
    git commit -m $Message --no-gpg-sign

    if ($NoPush) {
        Write-Host "\n(NoPush) Commit creado, push omitido." -ForegroundColor Yellow
        return
    }

    Write-Host "\n==> Push" -ForegroundColor Cyan
    git push
}

try {
    Assert-GitRepo

    $repoRoot = Get-RepoRoot
    $maxBytes = [long]$MaxSizeMB * 1MB

    Assert-ThereIsSomethingToCommit

    Stage-FilterLargeFiles -MaxBytes $maxBytes -RepoRoot $repoRoot

    $finalMessage = Ensure-CommitMessage -Message $Message

    # Segundo guard por si algo cambió entre medias
    Assert-NoLargeFilesStaged -MaxBytes $maxBytes -RepoRoot $repoRoot
    Assert-NoSensitiveTextStaged

    Do-CommitAndPush -Message $finalMessage -NoPush:$NoPush

    Write-Host "\nListo." -ForegroundColor Green
} catch {
    Write-Host "\nERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
