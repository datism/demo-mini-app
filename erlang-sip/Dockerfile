FROM erlang:26.2.3 AS builder

WORKDIR /app

# Cài rebar3 từ file local trong repository
COPY rebar3 /usr/local/bin/rebar3
RUN chmod +x /usr/local/bin/rebar3

COPY . .
RUN sed -i 's/\r$//' config/vm.args.src
RUN rebar3 release

# ---
# Stage 2: Runtime image
# Sử dụng erlang:26.2.3-slim (cùng nền tảng Debian với builder) để đảm bảo đồng bộ phiên bản GLIBC 100%
# (Nếu chính sách doanh nghiệp bắt buộc dùng RedHat UBI, bạn cần build Erlang trên UBI để tránh lệch phiên bản GLIBC).
FROM erlang:26.2.3-slim

RUN apt-get update && apt-get install -y ca-certificates iproute2 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /app/_build/default/rel/ims_dc_sip ./

# Config: DCSF URL có thể override bằng env var
ENV DCSF_URL="http://127.0.0.1:8080"

EXPOSE 5060/udp
CMD ["/app/bin/ims_dc_sip", "foreground"]
