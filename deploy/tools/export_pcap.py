import sys
import json
import urllib.request
import struct

def export_pcap(call_id, out_file):
    # Truy vấn cơ sở dữ liệu ClickHouse
    query = f"SELECT timestamp_ns, string FROM qryn.samples_v3 WHERE string LIKE '%Call-ID: {call_id}%' ORDER BY timestamp_ns ASC"
    url = "http://localhost:8123/?database=qryn&default_format=JSON"
    
    try:
        req = urllib.request.Request(url, data=query.encode('utf-8'), method='POST')
        with urllib.request.urlopen(req) as response:
            res_data = json.loads(response.read().decode('utf-8'))
    except Exception as e:
        print(f"Lỗi truy vấn ClickHouse: {e}")
        return

    rows = res_data.get('data', [])
    if not rows:
        print(f"Không tìm thấy gói tin nào khớp với Call-ID: {call_id}")
        return

    print(f"Tìm thấy {len(rows)} gói tin. Đang tiến hành tạo file PCAP...")
    
    with open(out_file, 'wb') as f:
        # Ghi PCAP Global Header (24 bytes)
        # Magic (4B), VerMajor (2B), VerMinor (2B), TZ (4B), Sigfigs (4B), Snaplen (4B), LinkType (4B)
        f.write(struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1))
        
        for row in rows:
            time_ns = int(row['timestamp_ns'])
            sec = time_ns // 1000000000
            usec = (time_ns % 1000000000) // 1000
            
            payload = row['string'].encode('utf-8')
            
            # Ethernet header (14 bytes)
            eth = struct.pack('!6s6sH', b'\x00'*6, b'\x00'*6, 0x0800)
            
            # IP header (20 bytes)
            ip_len = 20 + 8 + len(payload)
            ip = struct.pack('!BBHHHBBH4s4s', 
                             0x45, 0, ip_len, 0, 0, 64, 17, 0, 
                             bytes([127,0,0,1]), bytes([127,0,0,1]))
            
            # UDP header (8 bytes)
            udp_len = 8 + len(payload)
            udp = struct.pack('!HHHH', 5060, 5060, udp_len, 0)
            
            packet = eth + ip + udp + payload
            
            # Ghi Packet Header (16 bytes)
            # TsSec (4B), TsUsec (4B), CapLen (4B), OrigLen (4B)
            f.write(struct.pack('<IIII', sec, usec, len(packet), len(packet)))
            f.write(packet)
            
    print(f"Xuất file PCAP thành công tại: {out_file}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Sử dụng: python export_pcap.py <Call-ID> [out_file.pcap]")
        sys.exit(1)
    cid = sys.argv[1]
    outfile = sys.argv[2] if len(sys.argv) > 2 else f"call_{cid.replace('@', '_').replace('.', '_')}.pcap"
    export_pcap(cid, outfile)
