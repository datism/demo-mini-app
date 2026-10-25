#!/bin/bash
# Script triển khai offline tự động cho cụm Kubernetes (RHEL / Kubeadm / containerd)
# Cách chạy: chmod +x deploy-offline.sh && ./deploy-offline.sh [REGISTRY_ADDRESS]
# Ví dụ: ./deploy-offline.sh 192.168.1.100:5000

set -e

REGISTRY=${1:-"registry.local:5000"}

echo "====================================================================="
echo "   Hệ thống Triển khai Offline IMS DC - K8s Multi-Node (RedHat / RHEL) "
echo "   Local Registry sử dụng: $REGISTRY"
echo "====================================================================="

# 1. Tạo Namespace nếu chưa tồn tại
echo -e "\n[Bước 1/4] Tạo Namespace 'demo-mini-app'..."
kubectl create namespace demo-mini-app || true

# 2. Cập nhật các file YAML trỏ về Local Registry ngoại tuyến
echo -e "\n[Bước 2/4] Cập nhật các file YAML trỏ hình ảnh về Registry nội bộ..."
# Thay thế các tag image trong tất cả các file manifest YAML sang registry nội bộ
sed -i "s|image: go-mf:local|image: $REGISTRY/go-mf:local|g" k8s/core/*.yaml
sed -i "s|image: erlang-sip:local|image: $REGISTRY/erlang-sip:local|g" k8s/core/*.yaml
sed -i "s|image: postgres-patroni:local|image: $REGISTRY/postgres-patroni:local|g" k8s/monitoring/*.yaml
sed -i "s|image: clickhouse/clickhouse-server:|image: $REGISTRY/clickhouse/clickhouse-server:|g" k8s/monitoring/*.yaml
sed -i "s|image: redis:|image: $REGISTRY/redis:|g" k8s/core/*.yaml
sed -i "s|image: registry.k8s.io/coredns/coredns:v1.14.2|image: $REGISTRY/coredns/coredns:v1.14.2|g" k8s/core/*.yaml
sed -i "s|image: ghcr.io/k8snetworkplumbingwg/multus-cni:snapshot|image: $REGISTRY/k8snetworkplumbingwg/multus-cni:snapshot|g" k8s/core/*.yaml
sed -i "s|image: sipcapture/heplify:|image: $REGISTRY/sipcapture/heplify:|g" k8s/monitoring/*.yaml
sed -i "s|image: qxip/qryn:|image: $REGISTRY/qxip/qryn:|g" k8s/monitoring/*.yaml
sed -i "s|image: sipcapture/heplify-server:|image: $REGISTRY/sipcapture/heplify-server:|g" k8s/monitoring/*.yaml
sed -i "s|image: sipcapture/homer-app:|image: $REGISTRY/sipcapture/homer-app:|g" k8s/monitoring/*.yaml
sed -i "s|image: grafana/grafana-oss:|image: $REGISTRY/grafana/grafana-oss:|g" k8s/monitoring/*.yaml
sed -i "s|image: fluent/fluent-bit:|image: $REGISTRY/fluent/fluent-bit:|g" k8s/monitoring/*.yaml
sed -i "s|image: python:3.10-slim|image: $REGISTRY/python:3.10-slim|g" k8s/core/*.yaml k8s/monitoring/*.yaml
sed -i "s|image: busybox:1.28|image: $REGISTRY/busybox:1.28|g" k8s/core/*.yaml
sed -i "s|image: tailscale/tailscale:latest|image: $REGISTRY/tailscale/tailscale:latest|g" k8s/core/*.yaml k8s/addons/tailscale/*.yaml

# 3. Triển khai cấu hình mạng Multus & Macvlan
echo -e "\n[Bước 3/4] Cài đặt Multus CNI và cấu hình card mạng vật lý Macvlan..."
echo "LƯU Ý: Đảm bảo bạn đã cấu hình đúng tên card mạng vật lý 'master' (ví dụ: enp3s0, eth1...)"
echo "trong file 'k8s/core/k8s-multus-macvlan.yaml' trước khi áp dụng."
kubectl apply -f k8s/core/install-multus.yaml
kubectl apply -f k8s/core/k8s-multus-macvlan.yaml


# 4. Triển khai DNS Server nội bộ và cụm IMS Core
echo -e "\n[Bước 4/4] Khởi chạy DNS Server và các ứng dụng lõi IMS..."
kubectl apply -f k8s/core/k8s-dns-server.yaml
kubectl apply -f k8s/core/k8s-deployment.yaml
kubectl apply -f k8s/core/k8s-redis.yaml

echo -e "\n[TÙY CHỌN] Nếu cần Tailscale (Truy cập từ xa) hoặc Giám sát (Monitoring), chạy thêm:"
echo "kubectl apply -f k8s/addons/tailscale/"
echo "kubectl apply -f k8s/monitoring/"


echo -e "\n====================================================================="
echo "   Chúc mừng! Quá trình triển khai hoàn tất thành công.             "
echo "   Kiểm tra trạng thái hệ thống: kubectl get pods -n demo-mini-app  "
echo "====================================================================="
