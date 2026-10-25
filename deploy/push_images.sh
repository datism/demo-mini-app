#!/bin/bash
set -e

# Địa chỉ Registry nội bộ (Ví dụ chạy: ./push_images.sh 192.168.1.100:5000)
REGISTRY=${1:-"registry.local:5000"}
IMAGE_DIR="./offline-images"

echo "========================================="
echo "  Registry đích: $REGISTRY"
echo "  Đang nạp và đẩy toàn bộ Image ngoại tuyến"
echo "========================================="

# 1. Nạp và đẩy các base images từ file tar
if [ -d "$IMAGE_DIR" ]; then
    for tarfile in "$IMAGE_DIR"/*.tar; do
        [ -e "$tarfile" ] || continue
        echo "--> [ĐANG NẠP] $tarfile..."
        loaded_output=$(docker load -i "$tarfile")
        echo "$loaded_output"
        
        # Trích xuất tên và tag của image vừa load
        img=$(echo "$loaded_output" | awk '/Loaded image/ {print $3}')
        if [ -n "$img" ]; then
            # Loại bỏ các prefix domain cũ để tạo tag mới cho Registry nội bộ
            clean_img=$(echo "$img" | sed -E 's|^[^/]+/[^/]+/||; s|^[^/]+/||')
            new_img="$REGISTRY/$clean_img"
            
            echo "--> [TAGGING] $img -> $new_img"
            docker tag "$img" "$new_img"
            echo "--> [PUSHING] $new_img..."
            docker push "$new_img"
        fi
    done
else
    echo "⚠️ Không tìm thấy thư mục $IMAGE_DIR để nạp file tar!"
fi

# 2. Biên dịch offline và đẩy 2 Image ứng dụng cốt lõi lên Registry
echo "--> [BIÊN DỊCH] go-mf (Offline)..."
docker build -t go-mf:local ../go-mf
docker tag go-mf:local "$REGISTRY/go-mf:local"
docker push "$REGISTRY/go-mf:local"

echo "--> [BIÊN DỊCH] erlang-sip (Offline)..."
docker build -t erlang-sip:local ../erlang-sip
docker tag erlang-sip:local "$REGISTRY/erlang-sip:local"
docker push "$REGISTRY/erlang-sip:local"

echo "========================================="
echo "  Đã đẩy thành công toàn bộ lên Local Registry!"
echo "========================================="
