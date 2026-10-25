-module(hep_client).
-export([send_hep/5, send_hep/6, send_hep/7]).

send_hep(Payload, SrcIP, SrcPort, DstIP, DstPort) ->
    send_hep(Payload, SrcIP, SrcPort, DstIP, DstPort, 1).

send_hep(Payload, SrcIP, SrcPort, DstIP, DstPort, ProtoType) ->
    send_hep(Payload, SrcIP, SrcPort, DstIP, DstPort, ProtoType, "").

send_hep(Payload, SrcIP, SrcPort, DstIP, DstPort, ProtoType, CallID) ->
    case os:getenv("ENABLE_HEP_SELF_LOGGING") of
        "true" ->
            case os:getenv("HEP_SERVER_HOST") of
                false -> ok;
                "" -> ok;
                Host ->
                    PortStr = case os:getenv("HEP_SERVER_PORT") of
                        false -> "9060";
                        "" -> "9060";
                        P -> P
                    end,
                    Port = list_to_integer(PortStr),
                    spawn(fun() ->
                        try
                    {SA, SB, SC, SD} = parse_ip(SrcIP),
                    {DA, DB, DC, DD} = parse_ip(DstIP),
                    
                    %% Chunk 1: IP Protocol Family (IPv4 = 2)
                    Chunk1 = <<0:16, 1:16, 7:16, 2:8>>,
                    
                    %% Chunk 2: IP Protocol ID (UDP = 17)
                    Chunk2 = <<0:16, 2:16, 7:16, 17:8>>,
                    
                    %% Chunk 3: Source IP
                    Chunk3 = <<0:16, 3:16, 10:16, SA:8, SB:8, SC:8, SD:8>>,
                    
                    %% Chunk 4: Destination IP
                    Chunk4 = <<0:16, 4:16, 10:16, DA:8, DB:8, DC:8, DD:8>>,
                    
                    %% Chunk 7: Source Port
                    Chunk7 = <<0:16, 7:16, 8:16, SrcPort:16>>,
                    
                    %% Chunk 8: Destination Port
                    Chunk8 = <<0:16, 8:16, 8:16, DstPort:16>>,
                    
                    %% Chunk 9: Timestamp Seconds
                    {Mega, Secs, Micro} = os:timestamp(),
                    EpochSeconds = Mega * 1000000 + Secs,
                    Chunk9 = <<0:16, 9:16, 10:16, EpochSeconds:32>>,
                    
                    %% Chunk 10: Timestamp Microseconds
                    Chunk10 = <<0:16, 10:16, 10:16, Micro:32>>,
                    
                    %% Chunk 11: Protocol Type (1 = SIP, 100 = HTTP)
                    Chunk11 = <<0:16, 11:16, 7:16, ProtoType:8>>,
                    
                    %% Chunk 12: Capture Agent ID (4 bytes)
                    Chunk12 = <<0:16, 12:16, 10:16, 2026:32>>,

                    %% Chunk 17 (0x0011): Correlation ID
                    Chunk17 = case CallID of
                        "" -> <<>>;
                        _ ->
                            CallIDBin = if is_list(CallID) -> list_to_binary(CallID);
                                           true -> CallID
                                        end,
                            CallIDLen = byte_size(CallIDBin),
                            Chunk17Len = 6 + CallIDLen,
                            <<0:16, 17:16, Chunk17Len:16, CallIDBin/binary>>
                    end,
                    
                    %% Chunk 15: Payload
                    PayloadBin = if is_list(Payload) -> list_to_binary(Payload);
                                    true -> Payload
                                 end,
                    PayloadLen = byte_size(PayloadBin),
                    Chunk15Len = 6 + PayloadLen,
                    Chunk15 = <<0:16, 15:16, Chunk15Len:16, PayloadBin/binary>>,
                    
                    HEPPayload = <<Chunk1/binary, Chunk2/binary, Chunk3/binary, Chunk4/binary,
                                   Chunk7/binary, Chunk8/binary, Chunk9/binary, Chunk10/binary,
                                   Chunk11/binary, Chunk12/binary, Chunk17/binary, Chunk15/binary>>,
                                   
                    TotalLen = 6 + byte_size(HEPPayload),
                    HEPPacket = <<"HEP3", TotalLen:16, HEPPayload/binary>>,
                    
                    {ok, Socket} = gen_udp:open(0, [binary]),
                    ok = gen_udp:send(Socket, Host, Port, HEPPacket),
                    gen_udp:close(Socket)
                catch
                    Type:Reason ->
                        io_format("[HEP] ❌ Error sending HEP: ~p:~p~n", [Type, Reason])
                end
            end)
    end;
_ -> ok
end.

parse_ip(IP) when is_tuple(IP) -> IP;
parse_ip(IP) when is_list(IP) ->
    case inet:parse_address(IP) of
        {ok, Parsed} -> Parsed;
        _ -> {127, 0, 0, 1}
    end.

io_format(Fmt) -> io_format(Fmt, []).

io_format(Fmt, Args) ->
    RawMsg = io_lib:format(Fmt, Args),
    MsgStr = lists:flatten(RawMsg),
    CleanMsg = case lists:reverse(MsgStr) of
        [$\n | Rest] -> lists:reverse(Rest);
        _ -> MsgStr
    end,
    Lower = string:to_lower(CleanMsg),
    Level = case string:str(Lower, "error") > 0 orelse string:str(Lower, "failed") > 0 orelse string:str(CleanMsg, "❌") > 0 of
        true -> "ERROR";
        false ->
            case string:str(Lower, "warn") > 0 orelse string:str(CleanMsg, "⚠️") > 0 of
                true -> "WARN";
                false -> "INFO"
            end
    end,
    {Module, FinalMsg} = case CleanMsg of
        [$[ | Rest1] ->
            case string:split(Rest1, "]") of
                [Tag, Body] -> {string:to_lower(Tag), string:trim(Body)};
                _ -> {"system", CleanMsg}
            end;
        _ ->
            {"system", CleanMsg}
    end,
    logger_json:log_msg(Level, Module, "", FinalMsg).
