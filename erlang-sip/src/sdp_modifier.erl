%% =============================================================================
%% @doc SDP Modifier — Anchor DataChannel stream vào MF node
%%
%% Vai trò:
%%   Khi Erlang nhận INVITE từ Client A có SDP chứa m=application section,
%%   module này sẽ THAY THẾ toàn bộ tham số ICE/DTLS/địa chỉ trong section đó
%%   bằng thông tin của MF (Golang server) để "neo" luồng DataChannel vào MF.
%%
%% Theo 3GPP TS 23.228 Annex AC: IMS AS sửa đổi SDP trước khi forward
%% để mọi DataChannel traffic đều đi qua MF thay vì end-to-end.
%%
%% SDP Input mẫu (từ Client A):
%%   v=0
%%   o=- 1234 1 IN IP4 192.168.1.10
%%   s=-
%%   t=0 0
%%   m=application 9 UDP/DTLS/SCTP webrtc-datachannel
%%   c=IN IP4 192.168.1.10
%%   a=ice-ufrag:clientUfrag
%%   a=ice-pwd:clientPassword
%%   a=fingerprint:sha-256 CLIENT:FINGER:PRINT:...
%%   a=setup:actpass
%%   a=sctp-port:5000
%%   a=candidate:... udp ... 192.168.1.10 9000 ...
%%
%% SDP Output (sau khi anchor vào MF):
%%   v=0
%%   o=- 1234 1 IN IP4 192.168.1.100      <- MF IP
%%   s=-
%%   t=0 0
%%   m=application 40000 UDP/DTLS/SCTP webrtc-datachannel   <- MF port
%%   c=IN IP4 192.168.1.100               <- MF IP
%%   a=ice-ufrag:mfUfrag                  <- MF ICE ufrag
%%   a=ice-pwd:mfPassword                 <- MF ICE pwd
%%   a=fingerprint:sha-256 MF:DTLS:FP:...<- MF fingerprint
%%   a=setup:active                       <- Client phải là passive → MF active
%%   a=sctp-port:5000
%%   a=candidate:1 1 udp 2122260223 192.168.1.100 40000 typ host
%% =============================================================================
-module(sdp_modifier).

-export([anchor_to_mf/2]).

%% =============================================================================
%% anchor_to_mf/2
%%
%% SdpIn: SDP string từ Client A (chỉ phần body, không có SIP headers)
%% MfInfo: map từ DCSF response:
%%   #{
%%     session_id => "uuid...",
%%     ice_ufrag  => "xxxx",
%%     ice_pwd    => "yyyy",
%%     dtls_fingerprint => "sha-256 AA:BB:CC:...",
%%     ip   => "192.168.1.100",
%%     port => 40000,
%%     sdp_offer => "v=0\r\n..."   % Full SDP offer từ MF (optional)
%%   }
%%
%% Chiến lược: Parse từng dòng SDP, xác định m=application section,
%% thay thế các attribute liên quan đến ICE/DTLS/địa chỉ.
%% =============================================================================
anchor_to_mf(SdpIn, MfInfo) ->
    Lines = string:split(SdpIn, "\r\n", all),

    MfIP          = maps:get(ip,               MfInfo, "127.0.0.1"),
    MfPort        = maps:get(port,             MfInfo, 40000),
    MfUfrag       = maps:get(ice_ufrag,        MfInfo, "mfufrag"),
    MfPwd         = maps:get(ice_pwd,          MfInfo, "mfpassword"),
    MfFingerprint = maps:get(dtls_fingerprint, MfInfo, "sha-256 00:00"),

    %% Process: state machine qua từng dòng
    {ResultLines, _InApp} = lists:foldl(
        fun(Line, {Acc, InAppSection}) ->
            process_sdp_line(Line, Acc, InAppSection,
                             MfIP, MfPort, MfUfrag, MfPwd, MfFingerprint)
        end,
        {[], false},
        Lines
    ),

    string:join(lists:reverse(ResultLines), "\r\n").

%% =============================================================================
%% process_sdp_line/8
%% State machine: theo dõi xem có đang trong m=application section không
%% =============================================================================

process_sdp_line(Line, Acc, InApp, MfIP, MfPort, Ufrag, Pwd, FP) ->
    case string:prefix(Line, "m=application") of
        nomatch ->
            case InApp of
                true ->
                    case classify_sdp_attr(Line) of
                        ice_ufrag ->
                            {["a=ice-ufrag:" ++ Ufrag | Acc], true};
                        ice_pwd ->
                            {["a=ice-pwd:" ++ Pwd | Acc], true};
                        fingerprint ->
                            {["a=fingerprint:" ++ FP | Acc], true};
                        setup ->
                            %% Client cần là passive khi MF là active
                            {["a=setup:active" | Acc], true};
                        candidate ->
                            %% Xoá candidate của Client (MF đã thêm candidate riêng)
                            {Acc, true};
                        connection ->
                            %% c= line trong application section đã được thêm ở trên
                            {Acc, true};
                        discard ->
                            %% Bỏ các thuộc tính cũ tránh trùng lặp
                            {Acc, true};
                        other ->
                            {[Line | Acc], true}
                    end;
                false ->
                    {[Line | Acc], false}
            end;
        _ ->
            %% Phát hiện m=application section
            NewLine = "m=application " ++ integer_to_list(MfPort) ++
                      " UDP/DTLS/SCTP webrtc-datachannel",

            %% Ngay sau m=application, thêm c= với IP của MF
            CLine = "c=IN IP4 " ++ MfIP,

            ConnLine = "a=connection:new",
            SctpLine = "a=sctp-port:5000",
            DcmapLine = "a=dcmap:0 subprotocol=\"bfcp\"",

            %% Thêm ICE candidate của MF
            CandLine = build_mf_candidate(MfIP, MfPort),

            {[CandLine, DcmapLine, SctpLine, ConnLine, CLine, NewLine | Acc], true}
    end.

%% =============================================================================
%% classify_sdp_attr/1
%% Phân loại attribute dòng SDP
%% =============================================================================
classify_sdp_attr(Line) ->
    case string:prefix(Line, "a=ice-ufrag") of
        nomatch ->
            case string:prefix(Line, "a=ice-pwd") of
                nomatch ->
                    case string:prefix(Line, "a=fingerprint") of
                        nomatch ->
                            case string:prefix(Line, "a=setup") of
                                nomatch ->
                                    case string:prefix(Line, "a=candidate") of
                                        nomatch ->
                                            case string:prefix(Line, "c=") of
                                                nomatch ->
                                                    case string:prefix(Line, "a=connection") of
                                                        nomatch ->
                                                            case string:prefix(Line, "a=sctp-port") of
                                                                nomatch ->
                                                                    case string:prefix(Line, "a=dcmap") of
                                                                        nomatch -> other;
                                                                        _ -> discard
                                                                    end;
                                                                _ -> discard
                                                            end;
                                                        _ -> discard
                                                    end;
                                                _ -> connection
                                            end;
                                        _ -> candidate
                                    end;
                                _ -> setup
                            end;
                        _ -> fingerprint
                    end;
                _ -> ice_pwd
            end;
        _ -> ice_ufrag
    end.

%% =============================================================================
%% build_mf_candidate/2
%% Tạo ICE candidate string cho MF endpoint
%% Format: a=candidate:<foundation> <component> <transport> <priority>
%%                     <ip> <port> typ host
%% =============================================================================
build_mf_candidate(IP, Port) ->
    "a=candidate:1 1 udp 2122260223 " ++ IP ++ " " ++
    integer_to_list(Port) ++ " typ host generation 0 network-id 1".
