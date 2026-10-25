-module(n5_pcf_client).
-export([create_policy_session/5, delete_policy_session/1, update_flow_status/2]).

%% =============================================================================
%% N5 Interface Client (P-CSCF -> PCF)
%% Implements Npcf_PolicyAuthorization per TS 29.514
%% Protocol: HTTP (no TLS as per project rules)
%% =============================================================================

%% create_policy_session/5
%% Called by P-CSCF when processing initial INVITE with m=application (data channel)
%% Sends POST /npcf-policyauthorization/v1/app-sessions to PCF
create_policy_session(CallerID, CalledID, MediaType, QosHint, CallID) ->
    PcfHost = get_pcf_host(),
    PcfPort = get_pcf_port(),
    Url = lists:flatten(io_lib:format(
        "http://~s:~B/npcf-policyauthorization/v1/app-sessions", [PcfHost, PcfPort])),
    
    Body = jiffy:encode({[
        {<<"callerId">>, list_to_binary(CallerID)},
        {<<"calledId">>, list_to_binary(CalledID)},
        {<<"mediaType">>, list_to_binary(MediaType)},
        {<<"qosHint">>, list_to_binary(QosHint)}
    ]}),
    
    Headers = [{"Content-Type", "application/json"}],
    
    error_logger:info_msg("[N5-Client] Creating policy session: caller=~s, called=~s, mediaType=~s, qosHint=~s~n",
                          [CallerID, CalledID, MediaType, QosHint]),
    
    case send_request(post, Url, Headers, Body, CallID) of
        {ok, {{_, StatusCode, _}, _RespHeaders, RespBody}} when StatusCode >= 200, StatusCode < 300 ->
            Decoded = jiffy:decode(RespBody, [return_maps]),
            case Decoded of
                #{<<"appSessionId">> := SessionId, <<"qosData">> := QosData} ->
                    FiveQi = maps:get(<<"fiveQi">>, QosData, 69),
                    error_logger:info_msg("[N5-Client] Policy session created: id=~s, 5QI=~B~n",
                                          [SessionId, FiveQi]),
                    {ok, #{session_id => SessionId, five_qi => FiveQi}};
                Decoded ->
                    error_logger:info_msg("[N5-Client] Policy session created (partial decode): ~p~n", [Decoded]),
                    {ok, Decoded}
            end;
        {ok, {{_, StatusCode, Reason}, _, _}} ->
            error_logger:error_msg("[N5-Client] PCF returned ~B ~s~n", [StatusCode, Reason]),
            {error, {pcf_error, StatusCode}};
        {error, Reason} ->
            error_logger:error_msg("[N5-Client] Failed to contact PCF: ~p~n", [Reason]),
            {error, Reason}
    end.

%% delete_policy_session/1
%% Called by P-CSCF when processing BYE
delete_policy_session(SessionId) ->
    PcfHost = get_pcf_host(),
    PcfPort = get_pcf_port(),
    Url = lists:flatten(io_lib:format(
        "http://~s:~B/npcf-policyauthorization/v1/app-sessions/~s",
        [PcfHost, PcfPort, binary_to_list(SessionId)])),
    
    error_logger:info_msg("[N5-Client] Deleting policy session: ~s~n", [SessionId]),
    
    case send_request(delete, Url, [], <<>>, "") of
        {ok, {{_, 204, _}, _, _}} ->
            error_logger:info_msg("[N5-Client] Policy session deleted: ~s~n", [SessionId]),
            ok;
        {ok, {{_, StatusCode, _}, _, _}} ->
            error_logger:warning_msg("[N5-Client] PCF delete returned ~B~n", [StatusCode]),
            {error, StatusCode};
        {error, Reason} ->
            error_logger:error_msg("[N5-Client] Failed to delete session: ~p~n", [Reason]),
            {error, Reason}
    end.

%% update_flow_status/2
%% Called to update flow status (e.g., on hold/resume)
update_flow_status(SessionId, FlowStatus) ->
    PcfHost = get_pcf_host(),
    PcfPort = get_pcf_port(),
    Url = lists:flatten(io_lib:format(
        "http://~s:~B/npcf-policyauthorization/v1/app-sessions/~s",
        [PcfHost, PcfPort, binary_to_list(SessionId)])),
    
    Body = jiffy:encode({[{<<"flowStatus">>, list_to_binary(FlowStatus)}]}),
    Headers = [{"Content-Type", "application/json"}],
    
    error_logger:info_msg("[N5-Client] Updating flow status to ~s for session ~s~n",
                          [FlowStatus, SessionId]),
    
    case send_request(patch, Url, Headers, Body, "") of
        {ok, {{_, 200, _}, _, _}} ->
            ok;
        {ok, {{_, StatusCode, _}, _, _}} ->
            {error, StatusCode};
        {error, Reason} ->
            {error, Reason}
    end.

%% --- Internal helpers ---

get_pcf_host() ->
    case sys_config:load() of
        {ok, Config} ->
            Services = maps:get(<<"services">>, Config, #{}),
            PcfConfig = maps:get(<<"pcf">>, Services, #{}),
            binary_to_list(maps:get(<<"host">>, PcfConfig, <<"pcf">>));
        _ -> "pcf"
    end.

get_pcf_port() ->
    case sys_config:load() of
        {ok, Config} ->
            Services = maps:get(<<"services">>, Config, #{}),
            PcfConfig = maps:get(<<"pcf">>, Services, #{}),
            maps:get(<<"port">>, PcfConfig, 8085);
        _ -> 8085
    end.

send_request(Method, Url, Headers, Body, CallID) ->
    %% Parse URL parts for HEP
    {HostPart, PathPart} = parse_url_parts(Url),
    {DstHost, DstPort} = parse_url_host_port(Url),
    Net1IP = sys_config:get_net1_ip(),

    %% Format Request Header string
    ReqHeadersStr = lists:map(fun({K,V}) ->
        << (list_to_binary(K))/binary, ": ", (list_to_binary(V))/binary, "\r\n">>
    end, Headers),
    ReqHeadersBin = list_to_binary(ReqHeadersStr),

    %% Format Method string
    MethodStr = string:to_upper(atom_to_list(Method)),
    MethodBin = list_to_binary(MethodStr),

    %% Add X-Call-ID to HEP dump for pcap-exporter
    ExtraCallIdHeader = case CallID of
        "" -> <<>>;
        _ -> <<"X-Call-ID: ", (list_to_binary(CallID))/binary, "\r\n">>
    end,

    ReqDump = <<MethodBin/binary, " ", (list_to_binary(PathPart))/binary, " HTTP/1.1\r\nHost: ", 
                (list_to_binary(HostPart))/binary, "\r\n", 
                ExtraCallIdHeader/binary,
                ReqHeadersBin/binary, "\r\n", 
                Body/binary>>,

    %% Send Request to Homer via HEP (HTTP format, ProtoType = 100)
    catch hep_client:send_hep(ReqDump, Net1IP, 5060, DstHost, DstPort, 100, CallID),

    %% HTTP client request tuple
    ReqTuple = case Method of
        delete -> {Url, Headers};
        _ -> {Url, Headers, "application/json", binary_to_list(Body)}
    end,

    case httpc:request(Method, ReqTuple, [{timeout, 5000}], [{body_format, binary}]) of
        {ok, {{Version, StatusCode, Reason}, RespHeaders, RespBody}} ->
            RespDump = <<"HTTP/1.1 ", (list_to_binary(integer_to_list(StatusCode)))/binary, " OK\r\n",
                         ExtraCallIdHeader/binary,
                         "Content-Type: application/json\r\n\r\n", RespBody/binary>>,
            catch hep_client:send_hep(RespDump, DstHost, DstPort, Net1IP, 5060, 100, CallID),
            {ok, {{Version, StatusCode, Reason}, RespHeaders, RespBody}};
        {error, Reason} ->
            {error, Reason}
    end.

parse_url_host_port(Url) ->
    case string:split(Url, "://") of
        [_, Rest] ->
            case string:split(Rest, "/") of
                [HostPort, _] -> parse_host_port_str(HostPort);
                [HostPort] -> parse_host_port_str(HostPort)
            end;
        _ -> {"127.0.0.1", 8085}
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
