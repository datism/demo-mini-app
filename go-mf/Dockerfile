FROM 172.20.1.22/proxy-cache/library/golang:1.21.9-alpine3.19 AS builder

WORKDIR /app
COPY . .
RUN CGO_ENABLED=0 go test -mod=vendor -v ./internal/...
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /ims-as ./cmd/ims-as
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /dcsf ./cmd/dcsf
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /dcar ./cmd/dcar
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /mf ./cmd/mf
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /dcas ./cmd/dcas
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /pcf ./cmd/pcf
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /pcap-exporter ./cmd/pcap-exporter
RUN CGO_ENABLED=0 GOOS=linux go build -mod=vendor -o /dc-test-tool ./cmd/dc-test-tool

# ---
FROM 172.20.1.22/proxy-cache/library/alpine:3.19.1

WORKDIR /app
COPY --from=builder /ims-as .
COPY --from=builder /dcsf .
COPY --from=builder /dcar .
COPY --from=builder /mf .
COPY --from=builder /dcas .
COPY --from=builder /pcf .
COPY --from=builder /pcap-exporter .
COPY --from=builder /dc-test-tool .
COPY static/ ./static/

# Copy pre-converted media files for offline runtime support
COPY static/video.h264 ./video.h264
COPY static/video.pcma ./video.pcma
COPY static/agent.pcma ./agent.pcma
COPY static/unreachable.pcma ./unreachable.pcma

EXPOSE 8080 8081 8082 8083 8084 8085 8095 5060/udp 40000/udp
