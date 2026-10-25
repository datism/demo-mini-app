%% =============================================================================
%% @doc HTTP Client — Gọi Golang DCSF REST API từ Erlang
%%
%% Sử dụng thư viện hackney để gọi HTTP.
%% Nếu không muốn dùng hackney, có thể dùng httpc (built-in Erlang):
%%   application:start(inets), httpc:request(post, ...)
%%
%% API được gọi:
%%   1. POST /allocate_dc  — Yêu cầu MF tạo WebRTC PeerConnection
%%   2. POST /link_sessions — Ghép cặp A và B sau khi SIP setup xong
%% =============================================================================
-module(http_client).

-export([
    allocate_dc/3,
    allocate_hotline/4,
    link_sessions/4,
    set_remote_answer/3
]).

%% =============================================================================
%% allocate_dc/3
%%
%% Gọi POST {DcsfUrl}/allocate_dc để MF cấp phát 1 DataChannel session.
%%
%% Return:
%%   {ok, #{session_id, ice_ufrag, ice_pwd, dtls_fingerprint, ip, port, sdp_offer}}
%%   {error, Reason}
%% =============================================================================
allocate_dc(DcsfUrl, UserId, CallId) ->
    Url = DcsfUrl ++ "/allocate_dc",
    Body = jiffy:encode(#{
        <<"user_id">> => list_to_binary(UserId),
        <<"call_id">> => list_to_binary(CallId)
    }),
    Headers = [{<<"Content-Type">>, <<"application/json">>}],

    io_format("[HTTP] POST ~s body=~s~n", [Url, Body]),

    case do_post(Url, Headers, Body) of
        {ok, 200, RespBody} ->
            io_format("[HTTP] /allocate_dc OK: ~s~n", [RespBody]),
            parse_allocate_response(RespBody);
        {ok, StatusCode, RespBody} ->
            io_format("[HTTP] /allocate_dc error ~p: ~s~n", [StatusCode, RespBody]),
            {error, {http_error, StatusCode}};
        {error, Reason} ->
            io_format("[HTTP] /allocate_dc request failed: ~p~n", [Reason]),
            {error, Reason}
    end.

%% =============================================================================
%% allocate_hotline/4
%%
%% Gọi POST {DcsfUrl}/allocate_hotline để MF cấp phát hotline session và nhận SDP Answer.
%%
%% Return:
%%   {ok, #{session_id, sdp_answer}}
%%   {error, Reason}
%% =============================================================================
allocate_hotline(DcsfUrl, UserId, CallId, SdpOffer) ->
    Url = DcsfUrl ++ "/allocate_hotline",
    Body = jiffy:encode(#{
        <<"user_id">>   => list_to_binary(UserId),
        <<"call_id">>   => list_to_binary(CallId),
        <<"sdp_offer">> => list_to_binary(SdpOffer)
    }),
    Headers = [{<<"Content-Type">>, <<"application/json">>}],

    io_format("[HTTP] POST ~s body=~s~n", [Url, Body]),

    case do_post(Url, Headers, Body) of
        {ok, 200, RespBody} ->
            io_format("[HTTP] /allocate_hotline OK: ~s~n", [RespBody]),
            parse_hotline_response(RespBody);
        {ok, StatusCode, RespBody} ->
            io_format("[HTTP] /allocate_hotline error ~p: ~s~n", [StatusCode, RespBody]),
            {error, {http_error, StatusCode}};
        {error, Reason} ->
            io_format("[HTTP] /allocate_hotline request failed: ~p~n", [Reason]),
            {error, Reason}
    end.

parse_hotline_response(RespBody) ->
    try
        Json = jiffy:decode(list_to_binary(RespBody), [return_maps]),
        {ok, #{
            session_id => binary_to_list(maps:get(<<"session_id">>, Json)),
            sdp_answer => binary_to_list(maps:get(<<"sdp_answer">>, Json))
        }}
    catch
        _:Reason ->
            {error, {json_parse_error, Reason}}
    end.

%% =============================================================================
%% link_sessions/4
%%
%% Gọi POST {DcsfUrl}/link_sessions để MF ghép cặp A và B.
%% Phải gọi sau khi SIP handshake hoàn tất (sau ACK).
%%
%% Return: {ok, linked} | {error, Reason}
%% =============================================================================
link_sessions(DcsfUrl, CallId, SessionA, SessionB) ->
    Url = DcsfUrl ++ "/link_sessions",
    Body = jiffy:encode(#{
        <<"call_id">>   => list_to_binary(CallId),
        <<"session_a">> => list_to_binary(SessionA),
        <<"session_b">> => list_to_binary(SessionB)
    }),
    Headers = [{<<"Content-Type">>, <<"application/json">>}],

    io_format("[HTTP] POST ~s body=~s~n", [Url, Body]),

    case do_post(Url, Headers, Body) of
        {ok, 200, _} ->
            io_format("[HTTP] /link_sessions OK for call ~s~n", [CallId]),
            {ok, linked};
        {ok, StatusCode, RespBody} ->
            io_format("[HTTP] /link_sessions error ~p: ~s~n", [StatusCode, RespBody]),
            {error, {http_error, StatusCode}};
        {error, Reason} ->
            io_format("[HTTP] /link_sessions request failed: ~p~n", [Reason]),
            {error, Reason}
    end.

%% =============================================================================
%% Internal: Thực hiện HTTP POST
%%
%% Thử dùng hackney trước. Nếu không có, fallback về httpc (built-in).
%% =============================================================================
do_post(Url, Headers, Body) ->
    %% Prepare Request Dump for Homer Tracing
    {HostPart, PathPart} = parse_url_parts(Url),
    ReqHeadersStr = lists:map(fun({K,V}) ->
        <<K/binary, ": ", V/binary, "\r\n">>
    end, Headers),
    ReqHeadersBin = list_to_binary(ReqHeadersStr),
    ReqDump = <<"POST ", (list_to_binary(PathPart))/binary, " HTTP/1.1\r\nHost: ", (list_to_binary(HostPart))/binary, "\r\n", ReqHeadersBin/binary, "\r\n", (list_to_binary(Body))/binary>>,
    
    %% Mirror Request to Homer via HEP (HTTP format, ProtoType = 100)
    Net1IP = sys_config:get_net1_ip(),
    {DstHost, DstPort} = parse_url_host_port(Url),
    catch hep_client:send_hep(ReqDump, Net1IP, 5060, DstHost, DstPort, 100),

    %% Execute request
    Result = try
        do_post_hackney(Url, Headers, Body)
    catch
        error:undef ->
            io_format("[HTTP] hackney not available, using httpc~n"),
            do_post_httpc(Url, Body)
    end,

    %% Mirror Response to Homer via HEP (HTTP format, ProtoType = 100)
    case Result of
        {ok, StatusCode, RespBody} ->
            RespDump = <<"HTTP/1.1 ", (list_to_binary(integer_to_list(StatusCode)))/binary, " OK\r\nContent-Type: application/json\r\n\r\n", (list_to_binary(RespBody))/binary>>,
            catch hep_client:send_hep(RespDump, DstHost, DstPort, Net1IP, 5060, 100);
        _ -> ok
    end,
    Result.

parse_url_host_port(Url) ->
    case string:split(Url, "://") of
        [_, Rest] ->
            case string:split(Rest, "/") of
                [HostPort, _] -> parse_host_port_str(HostPort);
                [HostPort] -> parse_host_port_str(HostPort)
            end;
        _ -> {"127.0.0.1", 8080}
    end.

parse_host_port_str(HostPort) ->
    case string:split(HostPort, ":") of
        [Host, PortStr] -> {Host, list_to_integer(PortStr)};
        [Host] -> {Host, 80}
    end.

parse_url_parts(Url) ->
    case string:split(Url, "://") of
        [_, Rest] ->
            case string:split(Rest, "/") of
                [HostPort, Path] -> {HostPort, "/" ++ Path};
                [HostPort] -> {HostPort, "/"}
            end;
        _ -> {"127.0.0.1", "/"}
    end.

do_post_hackney(Url, Headers, Body) ->
    case hackney:post(list_to_binary(Url), Headers, Body, [with_body]) of
        {ok, StatusCode, _RespHeaders, RespBody} ->
            {ok, StatusCode, binary_to_list(RespBody)};
        {error, Reason} ->
            {error, Reason}
    end.

do_post_httpc(Url, Body) ->
    application:ensure_started(inets),
    application:ensure_started(ssl),
    Request = {Url,
               [{"Content-Type", "application/json"}],
               "application/json",
               Body},
    case httpc:request(post, Request, [{timeout, 10000}], []) of
        {ok, {{_, StatusCode, _}, _, RespBody}} ->
            {ok, StatusCode, RespBody};
        {error, Reason} ->
            {error, Reason}
    end.

%% =============================================================================
%% Parse JSON response từ /allocate_dc
%% =============================================================================
parse_allocate_response(RespBody) ->
    try
        Json = jiffy:decode(list_to_binary(RespBody), [return_maps]),
        {ok, #{
            session_id       => binary_to_list(maps:get(<<"session_id">>,       Json)),
            ice_ufrag        => binary_to_list(maps:get(<<"ice_ufrag">>,        Json)),
            ice_pwd          => binary_to_list(maps:get(<<"ice_pwd">>,          Json)),
            dtls_fingerprint => binary_to_list(maps:get(<<"dtls_fingerprint">>, Json)),
            ip               => binary_to_list(maps:get(<<"ip">>,               Json)),
            port             => maps:get(<<"port">>, Json),
            sdp_offer        => binary_to_list(maps:get(<<"sdp_offer">>,        Json, <<"">>))
        }}
    catch
        _:Reason ->
            {error, {json_parse_error, Reason}}
    end.

%% =============================================================================
%% set_remote_answer/3
%%
%% Gọi POST {DcsfUrl}/set_remote_answer để MF SetRemoteDescription(answer).
%% Được gọi bởi Erlang sau khi nhận 200 OK từ B, để ICE bắt đầu.
%%
%% Return: {ok, answered} | {error, Reason}
%% =============================================================================
set_remote_answer(DcsfUrl, SessionId, SdpAnswer) ->
    Url = DcsfUrl ++ "/set_remote_answer",
    Body = jiffy:encode(#{
        <<"session_id">> => list_to_binary(SessionId),
        <<"sdp_answer">>  => list_to_binary(SdpAnswer)
    }),
    Headers = [{<<"Content-Type">>, <<"application/json">>}],

    io_format("[HTTP] POST ~s session=~s~n", [Url, SessionId]),

    case do_post(Url, Headers, Body) of
        {ok, 200, _} ->
            io_format("[HTTP] /set_remote_answer OK for session ~s~n", [SessionId]),
            {ok, answered};
        {ok, StatusCode, RespBody} ->
            io_format("[HTTP] /set_remote_answer error ~p: ~s~n", [StatusCode, RespBody]),
            {error, {http_error, StatusCode}};
        {error, Reason} ->
            io_format("[HTTP] /set_remote_answer request failed: ~p~n", [Reason]),
            {error, Reason}
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
    CallID = case string:str(CleanMsg, "call ") > 0 of
        true ->
            Idx = string:str(CleanMsg, "call "),
            RestCall = string:sub_string(CleanMsg, Idx + 5),
            case string:tokens(RestCall, " \n\r\t") of
                [C | _] -> C;
                _ -> ""
            end;
        false ->
            case string:str(CleanMsg, "session=") > 0 of
                true ->
                    Idx2 = string:str(CleanMsg, "session="),
                    RestCall2 = string:sub_string(CleanMsg, Idx2 + 8),
                    case string:tokens(RestCall2, " \n\r\t") of
                        [C2 | _] -> C2;
                        _ -> ""
                    end;
                false -> ""
            end
    end,
    logger_json:log_msg(Level, Module, CallID, FinalMsg).
