#!/bin/bash
# deploy/precheck.sh
# Bash version of pre-flight check for AlmaLinux 8.10 / RedHat offline environment.

echo "========================================="
echo "   IMS DC SIMULATOR PRE-FLIGHT CHECK     "
echo "========================================="

has_error=0

# 1. Check Kubectl connectivity
echo -n "[1/5] Checking Kubernetes cluster connectivity..."
nodes=$(kubectl get nodes -o jsonpath='{.items[*].metadata.name}' 2>/dev/null)
if [ $? -ne 0 ] || [ -z "$nodes" ]; then
    echo -e " \e[31m[FAILED]\e[0m"
    echo -e "     \e[33mError: Cannot connect to Kubernetes cluster. Make sure kubectl is configured and cluster is running.\e[0m"
    has_error=1
else
    echo -e " \e[32m[OK]\e[0m (Found nodes: $nodes)"
fi

# 2. Check Node Selector compatibility
echo -n "[2/5] Checking Node Selector configuration..."
if [ -f "deploy/k8s-deployment.yaml" ]; then
    expected_nodes=$(grep -E "kubernetes.io/hostname:" deploy/*.yaml | awk '{print $NF}' | sort -u)
    missing_nodes=""
    for node in $expected_nodes; do
        if [[ ! " $nodes " =~ " $node " ]]; then
            missing_nodes="$missing_nodes $node"
        fi
    done
    
    if [ -n "$missing_nodes" ]; then
        echo -e " \e[31m[FAILED]\e[0m"
        echo -e "     \e[33mWarning: Manifests target node(s) [$missing_nodes] which do NOT exist in this cluster.\e[0m"
        echo -e "     \e[33mPlease edit nodeSelector in deploy/*.yaml or run patch script to change them to: $nodes\e[0m"
        has_error=1
    else
        echo -e " \e[32m[OK]\e[0m (Manifest selectors match cluster nodes)"
    fi
else
    echo -e " \e[33m[SKIPPED]\e[0m (Manifest files not found)"
fi

# 3. Check CNI (Multus) if Macvlan is enabled
echo -n "[3/5] Checking CNI configurations..."
if [ -f "deploy/k8s-deployment.yaml" ]; then
    uses_macvlan=$(grep "macvlan-conf" deploy/k8s-deployment.yaml)
    if [ -n "$uses_macvlan" ]; then
        kubectl get network-attachment-definitions -n demo-mini-app &>/dev/null
        if [ $? -ne 0 ]; then
            echo -e " \e[31m[FAILED]\e[0m"
            echo -e "     \e[33mError: Manifests require NetworkAttachmentDefinition (Macvlan), but Multus CNI is not installed.\e[0m"
            echo -e "     \e[33mInstall Multus first: kubectl apply -f deploy/install-multus.yaml\e[0m"
            has_error=1
        else
            echo -e " \e[32m[OK]\e[0m (Multus CNI active)"
        fi
    else
        echo -e " \e[32m[OK]\e[0m (Calico CNI mode, no Multus required)"
    fi
fi

# 4. Check Local Images
echo -n "[4/5] Checking required local Docker images..."
images_in_cluster=$(kubectl get nodes -o jsonpath='{.items[*].status.images[*].names[*]}' 2>/dev/null)
expected_images=("go-mf:local" "erlang-sip:local")
need_build=()
need_load=()

for img in "${expected_images[@]}"; do
    if [[ ! "$images_in_cluster" =~ "$img" ]]; then
        # Check if exists in local docker daemon
        docker_images=$(docker images --format "{{.Repository}}:{{.Tag}}" 2>/dev/null)
        if [[ "$docker_images" =~ "$img" ]]; then
            need_load+=("$img")
        else
            need_build+=("$img")
        fi
    fi
done

if [ ${#need_build[@]} -ne 0 ]; then
    echo -e " \e[31m[FAILED]\e[0m"
    for img in "${need_build[@]}"; do
        svc=$(echo "$img" | cut -d':' -f1)
        echo -e "     \e[33mError: Image '$img' not built. Build it: docker build -t $img ./$svc\e[0m"
    done
    has_error=1
elif [ ${#need_load[@]} -ne 0 ]; then
    echo -e " \e[31m[FAILED]\e[0m"
    echo -e "     \e[33mError: Images [${need_load[*]}] exist locally but not loaded to K8s node.\e[0m"
    echo -e "     \e[33mFor Kind, run: ./kind load docker-image ${need_load[*]}\e[0m"
    has_error=1
else
    echo -e " \e[32m[OK]\e[0m (All local images loaded to cluster)"
fi

# 5. Check offline base images
echo -n "[5/5] Checking Offline package packaging..."
if [ -d "offline-images" ]; then
    tar_count=$(ls offline-images/*.tar 2>/dev/null | wc -l)
    if [ "$tar_count" -eq 0 ]; then
        echo -e " \e[33m[WARNING]\e[0m"
        echo -e "     \e[33mNotice: No .tar images found in 'offline-images/'. Run save_images script on internet machine first.\e[0m"
    else
        echo -e " \e[32m[OK]\e[0m (Found $tar_count offline base image packages)"
    fi
else
    echo -e " \e[33m[WARNING]\e[0m"
    echo -e "     \e[33mNotice: 'offline-images/' directory is missing. Make sure to pack base images for air-gapped deploy.\e[0m"
fi

echo "========================================="
if [ $has_error -ne 0 ]; then
    echo -e "   \e[31mPRE-FLIGHT CHECK FAILED (Fix errors above)\e[0m"
else
    echo -e "   \e[32mPRE-FLIGHT CHECK PASSED! READY TO DEPLOY.\e[0m"
fi
echo "========================================="
