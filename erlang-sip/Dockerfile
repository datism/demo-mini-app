FROM 172.20.1.22/proxy-cache/erlang:26.2.3 AS builder

WORKDIR /app

# Cài rebar3 từ file local trong repository
COPY rebar3 /usr/local/bin/rebar3
RUN chmod +x /usr/local/bin/rebar3

COPY . .
RUN sed -i 's/\r$//' config/vm.args.src
RUN rm -rf _build .rebar3 local_deps/.rebar3 && \
    mkdir -p _checkouts && \
    cp -a local_deps/* _checkouts/ && \
    rm -rf _checkouts/.rebar3
RUN DIAGNOSTIC=1 DEBUG=1 rebar3 release

# ---
# Stage 2: Runtime image
# Sử dụng erlang:26.2.3-slim (cùng nền tảng Debian với builder) để đảm bảo đồng bộ phiên bản GLIBC 100%
# (Nếu chính sách doanh nghiệp bắt buộc dùng RedHat UBI, bạn cần build Erlang trên UBI để tránh lệch phiên bản GLIBC).
FROM 172.20.1.22/proxy-cache/erlang:26.2.3-slim
RUN rm -f /etc/apt/sources.list.d/*.sources /etc/apt/sources.list \
    && printf 'deb http://172.20.1.22:8081/repository/debian/ bookworm main\n\
    deb http://172.20.1.22:8081/repository/debian-security/ bookworm-security main\n\
    deb http://172.20.1.22:8081/repository/debian/ bookworm-updates main\n' \
       > /etc/apt/sources.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates iproute2 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /app/_build/default/rel/ims_dc_sip ./

# Config: DCSF URL có thể override bằng env var
ENV DCSF_URL="http://127.0.0.1:8080"

EXPOSE 5060/udp
CMD ["/app/bin/ims_dc_sip", "foreground"]
