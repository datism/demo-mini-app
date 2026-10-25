# deploy/precheck.ps1
# Script to verify all prerequisites before deploying the IMS Data Channel simulator.

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "   IMS DC SIMULATOR PRE-FLIGHT CHECK     " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

$hasError = $false

# 1. Check Kubectl connectivity
Write-Host "[1/5] Checking Kubernetes cluster connectivity..." -NoNewline
$nodes = kubectl get nodes -o jsonpath='{.items[*].metadata.name}' 2>$null
if ($LASTEXITCODE -ne 0 -or -not $nodes) {
    Write-Host " [FAILED]" -ForegroundColor Red
    Write-Host "     Error: Cannot connect to Kubernetes cluster. Make sure your kubeconfig is active and cluster is running." -ForegroundColor Yellow
    $hasError = $true
} else {
    Write-Host " [OK] (Found nodes: $nodes)" -ForegroundColor Green
}

# 2. Check Node Selector compatibility
Write-Host "[2/5] Checking Node Selector configuration..." -NoNewline
if (Test-Path "deploy/k8s-deployment.yaml") {
    $manifestContent = Get-Content "deploy/k8s-deployment.yaml" -Raw
    $matchedSelectors = [regex]::Matches($manifestContent, '(?m)kubernetes.io/hostname:\s*([^\s\r\n]+)')
    $missingNodes = @()
    
    foreach ($match in $matchedSelectors) {
        $expectedNode = $match.Groups[1].Value
        if ($nodes -notcontains $expectedNode) {
            if ($missingNodes -notcontains $expectedNode) {
                $missingNodes += $expectedNode
            }
        }
    }
    
    if ($missingNodes.Count -gt 0) {
        Write-Host " [FAILED]" -ForegroundColor Red
        Write-Host "     Warning: The manifests target node(s) [$($missingNodes -join ', ')] which do NOT exist in the cluster." -ForegroundColor Yellow
        Write-Host "     Please edit nodeSelector in deploy/*.yaml files or run patch script to change them to one of: $nodes" -ForegroundColor Yellow
        $hasError = $true
    } else {
        Write-Host " [OK] (Manifest selectors match cluster nodes)" -ForegroundColor Green
    }
} else {
    Write-Host " [SKIPPED] (k8s-deployment.yaml not found)" -ForegroundColor Yellow
}

# 3. Check CNI (Multus) installation if Macvlan is enabled
Write-Host "[3/5] Checking CNI configurations..." -NoNewline
if (Test-Path "deploy/k8s-deployment.yaml") {
    $usesMacvlan = Get-Content "deploy/k8s-deployment.yaml" | Select-String "macvlan-conf"
    if ($usesMacvlan) {
        $crd = kubectl get network-attachment-definitions -n demo-mini-app 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Host " [FAILED]" -ForegroundColor Red
            Write-Host "     Error: Manifests require NetworkAttachmentDefinition (Macvlan), but Multus CNI CRDs are not installed in the cluster." -ForegroundColor Yellow
            Write-Host "     To fix this, install Multus: kubectl apply -f deploy/install-multus.yaml" -ForegroundColor Yellow
            $hasError = $true
        } else {
            Write-Host " [OK] (Multus CNI active)" -ForegroundColor Green
        }
    } else {
        Write-Host " [OK] (Calico CNI mode, no Multus required)" -ForegroundColor Green
    }
}

# 4. Check Local Images
Write-Host "[4/5] Checking required local Docker images..." -NoNewline
$imagesInCluster = kubectl get nodes -o jsonpath='{.items[*].status.images[*].names[*]}' 2>$null
$expectedImages = @("go-mf:local", "erlang-sip:local")
$missingImages = @()

foreach ($img in $expectedImages) {
    if ($imagesInCluster -notmatch [regex]::Escape($img)) {
        $missingImages += $img
    }
}

if ($missingImages.Count -gt 0) {
    # Check if images exist in local Docker registry
    $dockerImages = docker images --format "{{.Repository}}:{{.Tag}}" 2>$null
    $needLoad = @()
    foreach ($img in $missingImages) {
        if ($dockerImages -match [regex]::Escape($img)) {
            $needLoad += $img
        } else {
            Write-Host " [FAILED]" -ForegroundColor Red
            Write-Host "     Error: Image '$img' not built. Build it first: docker build -t $img ./$($img.Split(':')[0])" -ForegroundColor Yellow
            $hasError = $true
        }
    }
    if ($needLoad.Count -gt 0) {
        Write-Host " [FAILED]" -ForegroundColor Red
        Write-Host "     Error: Images [$($needLoad -join ', ')] are built locally but not loaded into the Kind cluster nodes." -ForegroundColor Yellow
        Write-Host "     To fix this, run: .\kind.exe load docker-image $($needLoad -join ' ')" -ForegroundColor Yellow
        $hasError = $true
    }
} else {
    Write-Host " [OK] (All local images loaded to cluster)" -ForegroundColor Green
}

# 5. Check offline base images if deploy directory exists
Write-Host "[5/5] Checking Offline package packaging..." -NoNewline
if (Test-Path "deploy/save_images.ps1") {
    if (-not (Test-Path "offline-images")) {
        Write-Host " [WARNING]" -ForegroundColor Yellow
        Write-Host "     Notice: 'offline-images/' directory is missing. For air-gapped offline deploy, make sure to run deploy/save_images.ps1 on an internet-enabled machine first." -ForegroundColor Yellow
    } else {
        $tars = Get-ChildItem "offline-images/*.tar" 2>$null
        if ($tars.Count -eq 0) {
            Write-Host " [WARNING]" -ForegroundColor Yellow
            Write-Host "     Notice: No .tar images found in 'offline-images/'. Run save_images script to fetch base dependencies." -ForegroundColor Yellow
        } else {
            Write-Host " [OK] (Found $($tars.Count) offline base image packages)" -ForegroundColor Green
        }
    }
} else {
    Write-Host " [SKIPPED]" -ForegroundColor Yellow
}

Write-Host "=========================================" -ForegroundColor Cyan
if ($hasError) {
    Write-Host "   PRE-FLIGHT CHECK FAILED (Fix errors above)" -ForegroundColor Red
} else {
    Write-Host "   PRE-FLIGHT CHECK PASSED! READY TO DEPLOY." -ForegroundColor Green
}
Write-Host "=========================================" -ForegroundColor Cyan
