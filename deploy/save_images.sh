#!/bin/bash
set -e

# Thư mục lưu trữ các file tar
IMAGE_DIR="./offline-images"
mkdir -p "$IMAGE_DIR"

echo "========================================="
echo "  Tải và Lưu các Base Images Ngoại tuyến"
echo "========================================="

# Danh sách các image cần tải từ Internet về để chạy offline
IMAGES=(
    "golang:1.21.9-alpine3.19"
    "alpine:3.19.1"
    "erlang:26.2.3"
    "erlang:26.2.3-slim"
    "registry.k8s.io/coredns/coredns:v1.14.2"
    "ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot"
    "clickhouse/clickhouse-server:23.3-alpine"
    "qxip/qryn:3.2.38"
    "sipcapture/heplify-server:1.30"
    "grafana/grafana-oss:10.4.2"
    "postgres:14-alpine"
    "sipcapture/homer-app:1.1.4"
    "sipcapture/heplify:2.0.24"
    "fluent/fluent-bit:3.0"
    "redis:7.2.15-alpine"
    "python:3.10-slim"
    "busybox:1.28"
    "tailscale/tailscale:latest"
)

for img in "${IMAGES[@]}"; do
    # Chuyển đổi ký tự đặc biệt trong tên image thành dấu gạch dưới để đặt tên file tar
    filename=$(echo "$img" | tr '/:' '__')
    filepath="$IMAGE_DIR/$filename.tar"
    
    if [ -f "$filepath" ]; then
        echo "--> [BỎ QUA] $img đã được lưu tại $filepath"
    else
        echo "--> [ĐANG TẢI] $img từ Internet..."
        docker pull "$img"
        echo "--> [ĐANG LƯU] $img thành $filepath..."
        docker save -o "$filepath" "$img"
        echo "--> [HOÀN THÀNH] Đã lưu thành công $img!"
    fi
done

# Build and save custom postgres-patroni:local image
if [ -f "Dockerfile.patroni" ]; then
    echo "--> [BIÊN DỊCH & LƯU] postgres-patroni:local..."
    docker build -t postgres-patroni:local -f Dockerfile.patroni .
    docker save -o "$IMAGE_DIR/postgres-patroni_local.tar" postgres-patroni:local
fi

echo "========================================="
echo "  Tất cả các image đã được lưu tại $IMAGE_DIR"
echo "========================================="
