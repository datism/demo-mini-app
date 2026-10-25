-module(dns_discovery).
-export([get_nodes/2, ping_nodes/2, send_msg/4]).

%% Lấy danh sách các node name (dạng atom) của một Service cụ thể trong K8s
%% ServiceName: Tên Headless Service (ví dụ: "pcscf-service" hoặc FQDN đầy đủ)
%% NodeRole: Tên vai trò (ví dụ: "pcscf", "logic", "sipgw")
%% Trả về danh sách node dạng: ['pcscf@192.168.100.10', ...]
get_nodes(ServiceName, NodeRole) ->
    FQDN = case string:str(ServiceName, ".") of
        0 -> ServiceName ++ "." ++ sys_config:get_domain();
        _ -> ServiceName
    end,
    case inet:getaddrs(FQDN, inet) of
        {ok, IPs} ->
            [list_to_atom(NodeRole ++ "@" ++ inet:ntoa(IP)) || IP <- IPs];
        _ ->
            []
    end.

%% Thực hiện ping để kết nối Erlang Cluster với các node tìm thấy qua DNS
ping_nodes(ServiceName, NodeRole) ->
    Nodes = get_nodes(ServiceName, NodeRole),
    Results = [ {Node, net_adm:ping(Node)} || Node <- Nodes ],
    io_format("[DNS DISCOVERY] Ping results for ~s (~s): ~p~n", [ServiceName, NodeRole, Results]),
    Results.

%% Gửi Erlang Message tới một process đăng ký tên (Registered Process) trên tất cả các node thuộc Service
send_msg(ServiceName, NodeRole, ProcessName, Msg) ->
    Nodes = get_nodes(ServiceName, NodeRole),
    [ {ProcessName, Node} ! Msg || Node <- Nodes ].

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
