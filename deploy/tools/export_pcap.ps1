param (
    [Parameter(Mandatory=$true)]
    [string]$CallID,
    
    [string]$OutFile = ""
)

# Nếu không truyền tên file đầu ra, tự động sinh theo Call-ID
if ($OutFile -eq "") {
    $safeCallId = $CallID -replace '[^a-zA-Z0-9_-]', '_'
    $OutFile = "call_$safeCallId.pcap"
}

# 1. Truy vấn ClickHouse qua HTTP API
$query = "SELECT timestamp_ns, string FROM qryn.samples_v3 WHERE string LIKE '%Call-ID: $CallID%' ORDER BY timestamp_ns ASC"
$body = $query
$uri = "http://localhost:8123/?database=qryn&default_format=JSON"

Write-Host "Đang truy vấn dữ liệu từ ClickHouse cho Call-ID: $CallID..."

try {
    $response = Invoke-RestMethod -Uri $uri -Method Post -Body $body
} catch {
    Write-Error "Không thể kết nối đến ClickHouse Server: $_"
    return
}

$rows = $response.data
if ($rows.Count -eq 0) {
    Write-Host "Không tìm thấy gói tin nào khớp với Call-ID này trong ClickHouse." -ForegroundColor Yellow
    return
}

Write-Host "Tìm thấy $($rows.Count) gói tin báo hiệu. Đang khởi tạo tệp PCAP..." -ForegroundColor Green

# 2. Tạo luồng nhị phân ghi file PCAP
$stream = [System.IO.File]::OpenWrite($OutFile)
$writer = New-Object System.IO.BinaryWriter($stream)

try {
    # PCAP Global Header (24 bytes)
    $writer.Write([uint32]0xa1b2c3d4) # Magic number
    $writer.Write([uint16]2)          # Version Major
    $writer.Write([uint16]4)          # Version Minor
    $writer.Write([int32]0)           # GMT to local correction
    $writer.Write([uint32]0)          # Accuracy of timestamps
    $writer.Write([uint32]65535)      # Max length of captured packets
    $writer.Write([uint32]1)          # Data link type (1 = Ethernet)

    foreach ($row in $rows) {
        # Đọc thời gian dạng nanoseconds
        $timeNs = [int64]$row.timestamp_ns
        $sec = [uint32][math]::Floor($timeNs / 1000000000)
        $usec = [uint32][math]::Floor(($timeNs % 1000000000) / 1000)
        
        # Chuyển payload SIP/HTTP sang mảng bytes
        $payloadBytes = [System.Text.Encoding]::UTF8.GetBytes($row.string)
        
        # Tạo Header Ethernet (14 bytes)
        $ethHeader = [byte[]]@(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, # Destination MAC (Dummy)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, # Source MAC (Dummy)
            0x08, 0x00                          # Type: IPv4 (0x0800)
        )
        
        # Tạo Header IPv4 (20 bytes)
        $ipLen = 20 + 8 + $payloadBytes.Length
        $ipHeader = [byte[]]@(
            0x45, 0x00,                          # Version=4, IHL=5, TOS=0
            [byte]($ipLen -shr 8), [byte]($ipLen -and 0xff), # Total Length
            0x00, 0x01,                          # Identification
            0x00, 0x00,                          # Flags & Fragment
            0x40,                                # TTL = 64
            0x11,                                # Protocol = 17 (UDP)
            0x00, 0x00,                          # Checksum (Dummy)
            127, 0, 0, 1,                        # Source IP (127.0.0.1)
            127, 0, 0, 1                         # Destination IP (127.0.0.1)
        )
        
        # Tạo Header UDP (8 bytes)
        $udpLen = 8 + $payloadBytes.Length
        $udpHeader = [byte[]]@(
            0x13, 0xc4,                          # Source Port = 5060 (0x13c4)
            0x13, 0xc4,                          # Destination Port = 5060 (0x13c4)
            [byte]($udpLen -shr 8), [byte]($udpLen -and 0xff), # Length
            0x00, 0x00                           # Checksum (Dummy)
        )
        
        # Ghép toàn bộ mảng bytes dữ liệu
        $packetBytes = New-Object byte[] ($ethHeader.Length + $ipHeader.Length + $udpHeader.Length + $payloadBytes.Length)
        [System.Buffer]::BlockCopy($ethHeader, 0, $packetBytes, 0, $ethHeader.Length)
        [System.Buffer]::BlockCopy($ipHeader, 0, $packetBytes, $ethHeader.Length, $ipHeader.Length)
        [System.Buffer]::BlockCopy($udpHeader, 0, $packetBytes, ($ethHeader.Length + $ipHeader.Length), $udpHeader.Length)
        [System.Buffer]::BlockCopy($payloadBytes, 0, $packetBytes, ($ethHeader.Length + $ipHeader.Length + $udpHeader.Length), $payloadBytes.Length)
        
        # Ghi Packet Header (16 bytes) vào tệp
        $writer.Write($sec)
        $writer.Write($usec)
        $writer.Write([uint32]$packetBytes.Length) # Captured Length
        $writer.Write([uint32]$packetBytes.Length) # Original Length
        
        # Ghi mảng dữ liệu gói tin
        $writer.Write($packetBytes)
    }
}
finally {
    $writer.Close()
    $stream.Close()
}

Write-Host "Đã xuất file PCAP thành công tại: $(Get-Item $OutFile)" -ForegroundColor Green
