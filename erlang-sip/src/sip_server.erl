%% =============================================================================
%% @doc SIP Server — IMS Core Node (Role-based: P-CSCF, I-CSCF, S-CSCF)
%%
%% Roles:
%%   - P-CSCF: Access Point for UE. Maps Username -> Contact IP:Port.
%%             Forward UE REGISTER to I-CSCF, other requests to S-CSCF.
%%   - I-CSCF: Queries configuration to resolve S-CSCF and forwards REGISTER.
%%   - S-CSCF: Authenticates REGISTER (MD5 Digest), checks iFC.
%%             If 'webrtc-datachannel' in SDP, proxy to IMS-AS, else proxy to P-CSCF.
%% =============================================================================
-module(sip_server).
-behaviour(gen_server).

%% Public API
-export([start_link/0, start_tcp_listener/1, accept_loop/1]).

%% gen_server callbacks
-export([init/1,
         handle_call/3,
         handle_cast/2,
         handle_info/2,
         terminate/2,
         code_change/3]).

-define(SERVER, ?MODULE).

-record(state, {
    socket      :: gen_udp:socket(),
    sip_port    :: pos_integer(),
    dcsf_url    :: string()
}).

start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

init([]) ->
    {ok, _Config} = sys_config:load(),
    Role = sys_config:get_role(),

    case Role of
        "pcscf" ->
            ets:new(pcscf_contacts, [named_table, set, public, {keypos, 1}, {write_concurrency, true}, {read_concurrency, true}]),
            ets:new(pcscf_service_routes, [named_table, set, public, {keypos, 1}, {write_concurrency, true}, {read_concurrency, true}]);
        "scscf" ->
            ets:new(scscf_contacts, [named_table, set, public, {keypos, 1}, {write_concurrency, true}, {read_concurrency, true}]);
        _ -> ok
    end,
    ets:new(tcp_sockets, [named_table, set, public, {keypos, 1}, {write_concurrency, true}, {read_concurrency, true}]),

    %% Start periodic clustering timer (every 15 seconds)
    case Role of
        "scscf" -> catch timer:apply_interval(15000, dns_discovery, ping_nodes, ["scscf-service", "scscf"]);
        "pcscf" -> catch timer:apply_interval(15000, dns_discovery, ping_nodes, ["pcscf-service", "pcscf"]);
        _ -> ok
    end,

    Port = case sys_config:get_address(Role) of
               {_Host, P} -> P;
               _ -> 5060
           end,
    case gen_udp:open(Port, [binary, {active, true}, {reuseaddr, true}, inet]) of
        {ok, Socket} ->
            io_format("[IMS-CORE] ✅ Node (~s) node name is ~p, listening on UDP port ~p~n", [Role, node(), Port]),
            spawn(fun() -> start_tcp_listener(Port) end),
            {ok, #state{socket=Socket, sip_port=Port, dcsf_url=""}};
        {error, Reason} ->
            io_format("[IMS-CORE] ❌ Cannot open UDP port ~p: ~p~n", [Port, Reason]),
            {stop, Reason}
    end.

handle_info({udp, Socket, FromIP, FromPort, Data}, #state{socket=Socket} = State) ->
    case byte_size(Data) =< 4 of
        true ->
            %% SIP keep-alive (CRLF CRLF = 4 bytes), silently drop
            ok;
        false ->
            Msg = binary_to_list(Data),
            Role = sys_config:get_role(),
            FirstLine = get_first_line(Msg),
            io_format("[~s] Received packet (~p bytes) from ~s:~p: ~s~n",
                      [string:uppercase(Role), byte_size(Data), inet:ntoa(FromIP), FromPort, FirstLine]),
            %% Mirror to Homer via HEPv3
            Net1IP = sys_config:get_net1_ip(),
            catch hep_client:send_hep(Data, FromIP, FromPort, Net1IP, State#state.sip_port),
            spawn(fun() -> handle_sip_message(Msg, FromIP, FromPort, State) end)
    end,
    {noreply, State};

handle_info({sync_contact, Username, PathVal, ExpiryTime}, State) ->
    ets:insert(scscf_contacts, {Username, authenticated, PathVal, ExpiryTime}),
    SCSCFBin = base64:encode(term_to_binary({Username, authenticated, PathVal, ExpiryTime})),
    catch redis_client:query([<<"SET">>, <<"scscf:contact:", (list_to_binary(Username))/binary>>, SCSCFBin, <<"EX">>, 3600]),
    io_format("[S-CSCF] Synchronized contact from cluster with Expiry: ~s -> ~s (~p)~n", [Username, PathVal, ExpiryTime]),
    {noreply, State};

handle_info({sync_contact, Username, PathVal}, State) ->
    ExpiryTime = erlang:system_time(second) + 3600,
    ets:insert(scscf_contacts, {Username, authenticated, PathVal, ExpiryTime}),
    SCSCFBin = base64:encode(term_to_binary({Username, authenticated, PathVal, ExpiryTime})),
    catch redis_client:query([<<"SET">>, <<"scscf:contact:", (list_to_binary(Username))/binary>>, SCSCFBin, <<"EX">>, 3600]),
    io_format("[S-CSCF] Synchronized contact from cluster (legacy): ~s -> ~s~n", [Username, PathVal]),
    {noreply, State};

handle_info(_Info, State) ->
    {noreply, State}.

handle_call(_Request, _From, State) ->
    {reply, ok, State}.

handle_cast({tcp_msg, Msg, PeerIP, PeerPort}, State) ->
    Role = sys_config:get_role(),
    FirstLine = get_first_line(Msg),
    io_format("[~s] Received TCP packet (~p bytes) from ~s:~p: ~s~n",
              [string:uppercase(Role), length(Msg), inet:ntoa(PeerIP), PeerPort, FirstLine]),
    %% Mirror to Homer via HEPv3
    Net1IP = sys_config:get_net1_ip(),
    catch hep_client:send_hep(list_to_binary(Msg), PeerIP, PeerPort, Net1IP, State#state.sip_port),
    spawn(fun() -> handle_sip_message(Msg, PeerIP, PeerPort, State) end),
    {noreply, State};

handle_cast(_Msg, State) ->
    {noreply, State}.

terminate(_Reason, #state{socket=Socket}) ->
    gen_udp:close(Socket),
    ok.

code_change(_OldVsn, State, _Extra) ->
    {ok, State}.

%% =============================================================================
%% SIP Message Router
%% =============================================================================

handle_sip_message(Msg, FromIP, FromPort, State) ->
    case sip_parser:parse_method(Msg) of
        {error, _} ->
            %% Ignore keep-alives or malformed messages
            ok;
        {ok, "RESPONSE"} ->
            handle_sip_message_by_role(Msg, FromIP, FromPort, State);
        {ok, _} ->
            case sip_parser:get_header(Msg, "max-forwards") of
                undefined ->
                    handle_sip_message_by_role(Msg, FromIP, FromPort, State);
                Val ->
                    case string:to_integer(string:trim(Val)) of
                        {Int, _} when Int =< 0 ->
                            Role = sys_config:get_role(),
                            io_format("[~s] ❌ Max-Forwards reached 0. Rejecting with 483 Too Many Hops.~n", [string:uppercase(Role)]),
                            Response = sip_parser:build_error_response(Msg, 483, "Too Many Hops"),
                            send_sip_response(Response, FromIP, FromPort, State);
                        _ ->
                            handle_sip_message_by_role(Msg, FromIP, FromPort, State)
                    end
            end
    end.

handle_sip_message_by_role(Msg, FromIP, FromPort, State) ->
    Role = sys_config:get_role(),
    case Role of
        "pcscf" -> route_pcscf(Msg, FromIP, FromPort, State);
        "icscf" -> route_icscf(Msg, FromIP, FromPort, State);
        "scscf" -> route_scscf(Msg, FromIP, FromPort, State);
        _ ->
            io_format("[IMS-CORE] Unknown node role: ~s~n", [Role]),
            ok
    end.

%% =============================================================================
%% P-CSCF Node Logic
%% =============================================================================
route_pcscf(Msg, FromIP, FromPort, State) ->
    Method = case sip_parser:parse_method(Msg) of
                 {ok, M} -> M;
                 _ -> "RESPONSE"
             end,
    ScscfIPs = case sys_config:get_address("scscf") of
                   {TmpScscfHost, _} -> sys_config:resolve_ips_cached(TmpScscfHost);
                   _ -> []
               end,
    IcscfIPs = case sys_config:get_address("icscf") of
                   {TmpIcscfHost, _} -> sys_config:resolve_ips_cached(TmpIcscfHost);
                   _ -> []
               end,
    IsFromCore = lists:member(FromIP, ScscfIPs ++ IcscfIPs),
    IsFromClient = not IsFromCore,
    case Method of
        "REGISTER" ->
            case sip_parser:parse_register(Msg) of
                {ok, #{username := Username}} ->
                    IPStr = inet:ntoa(FromIP),
                    ets:insert(pcscf_contacts, {Username, IPStr, FromPort}),
                    ValueBin = base64:encode(term_to_binary({Username, IPStr, FromPort})),
                    catch redis_client:query([<<"SET">>, <<"pcscf:contact:", (list_to_binary(Username))/binary>>, ValueBin, <<"EX">>, 3600]),
                    io_format("[P-CSCF] Recorded contact: ~s -> ~s:~p~n", [Username, IPStr, FromPort]),
                    Net1IP = sys_config:get_net1_ip(),
                    PathVal = "<sip:" ++ Net1IP ++ ":5060;lr>",
                    MsgWithPath = sip_parser:add_header(Msg, "Path", PathVal),
                    io_format("[P-CSCF] Added Path: ~s~n", [PathVal]),
                    {IcscfHost, IcscfPort} = sys_config:get_address("icscf"),
                    forward_sip(MsgWithPath, IcscfHost, IcscfPort, State);
                _ -> ok
            end;
        "RESPONSE" ->
            FromUser = case sip_parser:get_header(Msg, "from") of
                            undefined -> undefined;
                            FromVal -> sip_parser:extract_username(FromVal)
                        end,
            ToUser = case sip_parser:get_header(Msg, "to") of
                          undefined -> undefined;
                          ToVal -> sip_parser:extract_username(ToVal)
                      end,
            %% Extract and record Service-Route if it's 200 OK for REGISTER
            CSeqVal = sip_parser:get_header(Msg, "cseq"),
            IsRegisterResponse = case CSeqVal of
                undefined -> false;
                _ -> string:find(string:lowercase(CSeqVal), "register") =/= nomatch
            end,
            case IsRegisterResponse of
                true ->
                    case sip_parser:get_header(Msg, "service-route") of
                        undefined -> ok;
                        SRVal ->
                            ets:insert(pcscf_service_routes, {ToUser, SRVal}),
                            SRBin = base64:encode(term_to_binary({ToUser, SRVal})),
                            catch redis_client:query([<<"SET">>, <<"pcscf:service_route:", (list_to_binary(ToUser))/binary>>, SRBin, <<"EX">>, 3600]),
                            io_format("[P-CSCF] Recorded Service-Route for ~s -> ~s~n", [ToUser, SRVal])
                    end;
                false -> ok
            end,

            case IsFromClient of
                true ->
                    %% Uplink response to Core
                    case lookup_pcscf_service_route(ToUser) of
                        {ok, SR} ->
                            {SRHost, SRPort} = sip_parser:parse_sip_uri(SR),
                            forward_sip(Msg, SRHost, SRPort, State);
                        error ->
                            {ScscfHost, ScscfPort} = sys_config:get_address("scscf"),
                            forward_sip(Msg, ScscfHost, ScscfPort, State)
                    end;
                false ->
                    %% Downlink response to client
                    case lookup_pcscf_contact(FromUser) of
                        {ok, IP3, Port3} -> forward_sip(Msg, IP3, Port3, State);
                        error ->
                            case lookup_pcscf_contact(ToUser) of
                                {ok, IP4, Port4} -> forward_sip(Msg, IP4, Port4, State);
                                error ->
                                    io_format("[P-CSCF] ⚠️ No registered client for response (To:~p, From:~p)~n", [ToUser, FromUser])
                            end
                    end
            end;
        _ ->
            %% Requests: INVITE, BYE, ACK, etc.
            FromUser = case sip_parser:get_header(Msg, "from") of
                            undefined -> undefined;
                            FromVal -> sip_parser:extract_username(FromVal)
                        end,
            ToUser = case sip_parser:get_header(Msg, "to") of
                           undefined -> undefined;
                           ToVal -> sip_parser:extract_username(ToVal)
                       end,
            case IsFromClient of
                true ->
                    %% Uplink request to Core
                    MsgWithIP = sip_parser:add_header(Msg, "X-Real-IP", inet:ntoa(FromIP)),
                    case lookup_pcscf_service_route(FromUser) of
                        {ok, SR} ->
                            {SRHost, SRPort} = sip_parser:parse_sip_uri(SR),
                            forward_sip(MsgWithIP, SRHost, SRPort, State);
                        error ->
                            {ScscfHost, ScscfPort} = sys_config:get_address("scscf"),
                            forward_sip(MsgWithIP, ScscfHost, ScscfPort, State)
                    end;
                false ->
                    %% Downlink request to client
                    case lookup_pcscf_contact(ToUser) of
                        {ok, IP2, Port2} ->
                            forward_sip(Msg, IP2, Port2, State);
                        error ->
                            CallID = case sip_parser:get_header(Msg, "call-id") of
                                         undefined -> "";
                                         C -> string:trim(C)
                                     end,
                            io_format("[P-CSCF] ❌ Contact for ~s not found. Replying 480 Temporarily Unavailable. CallID '~s'~n", [ToUser, CallID]),
                            Response = sip_parser:build_error_response(Msg, 480, "Temporarily Unavailable"),
                            send_sip_response(Response, FromIP, FromPort, State)
                    end
            end
    end.

lookup_pcscf_contact(undefined) -> error;
lookup_pcscf_contact(Username) ->
    case ets:lookup(pcscf_contacts, Username) of
        [{Username, IPStr, Port}] -> {ok, IPStr, Port};
        _ ->
            case catch redis_client:query([<<"GET">>, <<"pcscf:contact:", (list_to_binary(Username))/binary>>]) of
                {ok, Bin} when is_binary(Bin) ->
                    case catch binary_to_term(base64:decode(Bin)) of
                        {Username, IPStr, Port} = Record ->
                            ets:insert(pcscf_contacts, Record),
                            {ok, IPStr, Port};
                        _ -> error
                    end;
                _ -> error
            end
    end.

lookup_pcscf_service_route(undefined) -> error;
lookup_pcscf_service_route(Username) ->
    case ets:lookup(pcscf_service_routes, Username) of
        [{Username, SRVal}] -> {ok, SRVal};
        _ ->
            case catch redis_client:query([<<"GET">>, <<"pcscf:service_route:", (list_to_binary(Username))/binary>>]) of
                {ok, Bin} when is_binary(Bin) ->
                    case catch binary_to_term(base64:decode(Bin)) of
                        {Username, SRVal} = Record ->
                            ets:insert(pcscf_service_routes, Record),
                            {ok, SRVal};
                        _ -> error
                    end;
                _ -> error
            end
    end.

%% =============================================================================
%% I-CSCF Node Logic
%% =============================================================================
route_icscf(Msg, _FromIP, _FromPort, State) ->
    Method = case sip_parser:parse_method(Msg) of
                 {ok, M} -> M;
                 _ -> "RESPONSE"
             end,
    case Method of
        "RESPONSE" ->
            {PcscfHost, PcscfPort} = sys_config:get_address("pcscf"),
            forward_sip(Msg, PcscfHost, PcscfPort, State);
        _ ->
            {ScscfHost, ScscfPort} = sys_config:get_address("scscf"),
            forward_sip(Msg, ScscfHost, ScscfPort, State)
    end.

%% =============================================================================
%% S-CSCF Node Logic
%% =============================================================================
route_scscf(Msg, FromIP, FromPort, State) ->
    Method = case sip_parser:parse_method(Msg) of
                 {ok, M} -> M;
                 _ -> "RESPONSE"
             end,
    ImsAsIPs = case sys_config:get_address("ims_as") of
                   {Host, _} -> sys_config:resolve_ips_cached(Host);
                   _ -> []
               end,
    IsFromAS = lists:member(FromIP, ImsAsIPs),
    FromUser = case sip_parser:get_header(Msg, "from") of
                   undefined -> undefined;
                   FromVal -> sip_parser:extract_username(FromVal)
               end,
    IsCallerRegistered = case lookup_scscf_path(FromUser) of
                             {ok, _} -> true;
                             error -> false
                         end,
    case Method of
        "REGISTER" ->
            handle_scscf_register(Msg, FromIP, FromPort, State);
        "RESPONSE" ->
            case IsFromAS of
                true ->
                    %% Response returning from IMS-AS. Forward down to UE A (via P-CSCF)
                    forward_down_to_pcscf(Msg, State);
                false ->
                    %% Response from UE B (via P-CSCF). Forward up to IMS-AS
                    forward_to_ims_as(Msg, State)
            end;
        "INVITE" ->
            Contact = sip_parser:get_header(Msg, "contact"),
            Sdp = sip_parser:extract_sdp_body_pub(Msg),
            ToUserRaw = case sip_parser:get_header(Msg, "to") of
                            undefined -> "";
                            ToVal0 -> sip_parser:extract_username(ToVal0)
                        end,
            IsHotline = (ToUserRaw =:= "198") orelse (ToUserRaw =:= "+84198") orelse (ToUserRaw =:= "sip:198"),
            HasDC = (case Contact of
                         undefined -> false;
                         Val -> string:find(Val, "webrtc-datachannel") =/= nomatch
                     end) orelse (string:find(Sdp, "webrtc-datachannel") =/= nomatch) orelse IsHotline,
            _CallID = case sip_parser:get_header(Msg, "call-id") of
                         undefined -> "";
                         CidVal -> string:trim(CidVal)
                     end,
            case {IsFromAS, IsCallerRegistered, HasDC} of
                {false, false, _} ->
                    io_format("[S-CSCF] ❌ Caller ~s is not registered (DB reset/offline). Replying 430 Flow Active.~n", [FromUser]),
                    Response = sip_parser:build_error_response(Msg, 430, "Flow Active - Registration Lost"),
                    send_sip_response(Response, FromIP, FromPort, State);
                {true, _, _} ->
                    %% INVITE returning from IMS-AS after terminating leg.
                    %% Forward down to UE B (via P-CSCF)
                    ToUser = case sip_parser:get_header(Msg, "to") of
                                 undefined -> undefined;
                                 ToVal -> sip_parser:extract_username(ToVal)
                             end,
                    case lookup_scscf_path(ToUser) of
                        {ok, _} ->
                            forward_down_to_pcscf(Msg, State);
                        error ->
                            %% Not registered: a provisioned user is unreachable now (480, "không liên lạc được"); a number
                            %% that does not exist at all is 404 ("số không đúng"). The IMS-AS plays the matching announcement.
                            case lookup_user_password(ToUser) of
                                {ok, _} ->
                                    io_format("[S-CSCF] ❌ Callee ~s is offline/not registered. Replying 480 Temporarily Unavailable.~n", [ToUser]),
                                    Response = sip_parser:build_error_response(Msg, 480, "Temporarily Unavailable"),
                                    send_sip_response(Response, FromIP, FromPort, State);
                                error ->
                                    io_format("[S-CSCF] ❌ Callee ~p does not exist. Replying 404 Not Found.~n", [ToUser]),
                                    Response = sip_parser:build_error_response(Msg, 404, "Not Found"),
                                    send_sip_response(Response, FromIP, FromPort, State)
                            end
                    end;
                {false, true, true} ->
                    %% Initial INVITE matching iFC check.
                    %% N5 Interface: Create policy session on PCF for QoS provisioning
                    spawn(fun() ->
                        case sip_parser:parse_invite(Msg) of
                            {ok, InviteInfo} ->
                                CallerUser = maps:get(from_user, InviteInfo, "unknown"),
                                CalledUser = maps:get(to_user, InviteInfo, "unknown"),
                                CallID2 = maps:get(call_id, InviteInfo, "unknown"),
                                case n5_pcf_client:create_policy_session(
                                    CallerUser, CalledUser, "DC", "latency=200;loss=0.05", CallID2) of
                                     {ok, _Result} ->
                                         error_logger:info_msg("[P-CSCF] N5 policy session created for ~s -> ~s~n",
                                                               [CallerUser, CalledUser]);
                                     {error, Reason} ->
                                         error_logger:warning_msg("[P-CSCF] N5 policy creation failed (non-blocking): ~p~n",
                                                                   [Reason])
                                 end;
                             _ ->
                                 error_logger:warning_msg("[P-CSCF] Could not parse INVITE for N5 policy~n", [])
                        end
                    end),
                    %% Forward up to IMS-AS
                    forward_to_ims_as(Msg, State);
                {false, true, false} ->
                    %% INVITE that does not match iFC. Forward directly to UE B (via P-CSCF)
                    forward_down_to_pcscf(Msg, State)
            end;
        _ ->
            %% Forward BYE, ACK, etc.
            case IsFromAS of
                true ->
                    forward_down_to_pcscf(Msg, State);
                false ->
                    forward_to_ims_as(Msg, State)
            end
    end.

forward_to_ims_as(Msg, State) ->
    {ImsAsHost, ImsAsPort} = sys_config:get_address("ims_as"),
    IPs = sys_config:resolve_host(ImsAsHost),
    CallID = case sip_parser:get_header(Msg, "call-id") of
                 undefined -> "";
                 C -> string:trim(C)
             end,
    TargetIP = case IPs of
                   [] -> ImsAsHost;
                   [SingleIP] -> SingleIP;
                   _ ->
                       Index = erlang:phash2(CallID, length(IPs)),
                       lists:nth(Index + 1, IPs)
               end,
    io_format("[S-CSCF] Consistent routing for CallID '~s' -> Target IP ~s~n", [CallID, TargetIP]),
    forward_sip(Msg, TargetIP, ImsAsPort, State).

handle_scscf_register(Msg, FromIP, FromPort, State) ->
    case sip_parser:parse_register(Msg) of
        {ok, #{username := Username, expires := Expires}} ->
            case sip_parser:get_header(Msg, "authorization") of
                undefined ->
                    Nonce = generate_nonce(),
                    Domain = sys_config:get_domain(),
                    WWWAuth = "WWW-Authenticate: Digest realm=\"" ++ Domain ++ "\", nonce=\"" ++ Nonce ++ "\", algorithm=MD5\r\n",
                    Response = sip_parser:build_response(Msg, 401, "Unauthorized", WWWAuth),
                    send_sip_response(Response, FromIP, FromPort, State);
                AuthVal ->
                    AuthMap = parse_auth_header(AuthVal),
                    Nonce = maps:get(<<"nonce">>, AuthMap, <<"">>),
                    ResponseHash = maps:get(<<"response">>, AuthMap, <<"">>),
                    Uri = maps:get(<<"uri">>, AuthMap, <<"">>),

                    case lookup_user_password(Username) of
                        {ok, Password} ->
                            Realm = sys_config:get_domain(),
                            A1 = md5_hex(Username ++ ":" ++ Realm ++ ":" ++ Password),
                            A2 = md5_hex("REGISTER:" ++ binary_to_list(Uri)),
                            Expected = md5_hex(A1 ++ ":" ++ binary_to_list(Nonce) ++ ":" ++ A2),
                            case binary_to_list(ResponseHash) == Expected of
                                true ->
                                    PathVal = case sip_parser:get_header(Msg, "path") of
                                        undefined -> undefined;
                                        P -> P
                                    end,
                                    ExpiryTime = erlang:system_time(second) + Expires,
                                    ets:insert(scscf_contacts, {Username, authenticated, PathVal, ExpiryTime}),
                                    SCSCFBin = base64:encode(term_to_binary({Username, authenticated, PathVal, ExpiryTime})),
                                    catch redis_client:query([<<"SET">>, <<"scscf:contact:", (list_to_binary(Username))/binary>>, SCSCFBin, <<"EX">>, 3600]),
                                    io_format("[S-CSCF] User ~s authenticated successfully (Path: ~s, Expires: ~p)~n", [Username, PathVal, Expires]),
                                    %% Broadcast registration state to all other S-CSCF nodes in the cluster (non-blocking)
                                    spawn(fun() -> catch dns_discovery:send_msg("scscf-service", "scscf", ?SERVER, {sync_contact, Username, PathVal, ExpiryTime}) end),
                                    %% Service-Route points to S-CSCF itself (for P-CSCF internal routing)
                                    ServiceRoute = "<sip:" ++ sys_config:get_net1_ip() ++ ":5060;lr>",
                                    ExtraHeaders = "Service-Route: " ++ ServiceRoute ++ "\r\n",
                                    Response = sip_parser:build_200_ok_register(Msg, ExtraHeaders),
                                    send_sip_response(Response, FromIP, FromPort, State);
                                false ->
                                    io_format("[S-CSCF] ❌ Auth failed for ~s (hash mismatch)~n", [Username]),
                                    Response = sip_parser:build_error_response(Msg, 403, "Forbidden"),
                                    send_sip_response(Response, FromIP, FromPort, State)
                            end;
                        error ->
                            io_format("[S-CSCF] ❌ User ~s not found in registry~n", [Username]),
                            Response = sip_parser:build_error_response(Msg, 404, "Not Found"),
                            send_sip_response(Response, FromIP, FromPort, State)
                    end
            end;
        _ ->
            Response = sip_parser:build_error_response(Msg, 400, "Bad Request"),
            send_sip_response(Response, FromIP, FromPort, State)
    end.

%% =============================================================================
%% Shared Internal Utilities
%% =============================================================================
forward_sip(Msg0, Host, Port, State) ->
    Msg = case sip_parser:parse_method(Msg0) of
        {ok, "RESPONSE"} -> Msg0;
        _ ->
            sip_parser:decrement_or_add_max_forwards(Msg0)
    end,
    Role = sys_config:get_role(),
    FirstLine = get_first_line(Msg),
    io_format("[~s] Forwarding message to ~s:~p: ~s~n", [string:uppercase(Role), Host, Port, FirstLine]),
    HostIP = if
        is_list(Host) ->
            case inet:parse_address(Host) of
                {ok, ParsedIP} -> ParsedIP;
                _ -> Host
            end;
        true -> Host
    end,
    case ets:lookup(tcp_sockets, {HostIP, Port}) of
        [{{HostIP, Port}, CSock}] ->
            io_format("[DEBUG] Sending ~p bytes TCP. Last 4 chars: ~w~n", [length(Msg), lists:sublist(Msg, max(1, length(Msg)-3), 4)]),
            case gen_tcp:send(CSock, list_to_binary(Msg)) of
                ok ->
                    Net1IP = sys_config:get_net1_ip(),
                    catch hep_client:send_hep(Msg, Net1IP, State#state.sip_port, Host, Port),
                    ok;
                {error, Reason} ->
                    io_format("[IMS-CORE] ❌ Error forwarding TCP to ~s:~p: ~p~n", [Host, Port, Reason])
            end;
        [] ->
            case gen_udp:send(State#state.socket, Host, Port, list_to_binary(Msg)) of
                ok ->
                    Net1IP = sys_config:get_net1_ip(),
                    catch hep_client:send_hep(Msg, Net1IP, State#state.sip_port, Host, Port),
                    ok;
                {error, Reason} ->
                    io_format("[IMS-CORE] ❌ Error forwarding UDP to ~s:~p: ~p~n", [Host, Port, Reason])
            end
    end.

send_sip_response(Response, DestIP, DestPort, State) ->
    Role = sys_config:get_role(),
    FirstLine = get_first_line(Response),
    io_format("[~s] Sending response to ~s:~p: ~s~n", [string:uppercase(Role), format_ip(DestIP), DestPort, FirstLine]),
    case ets:lookup(tcp_sockets, {DestIP, DestPort}) of
        [{{DestIP, DestPort}, CSock}] ->
            io_format("[DEBUG] Sending ~p bytes TCP response. Last 4 chars: ~w~n", [length(Response), lists:sublist(Response, max(1, length(Response)-3), 4)]),
            case gen_tcp:send(CSock, list_to_binary(Response)) of
                ok ->
                    Net1IP = sys_config:get_net1_ip(),
                    catch hep_client:send_hep(Response, Net1IP, State#state.sip_port, format_ip(DestIP), DestPort),
                    ok;
                {error, Reason} ->
                    io_format("[IMS-CORE] ❌ Error sending TCP response to ~s:~p: ~p~n", [format_ip(DestIP), DestPort, Reason])
            end;
        [] ->
            case gen_udp:send(State#state.socket, DestIP, DestPort, list_to_binary(Response)) of
                ok ->
                    Net1IP = sys_config:get_net1_ip(),
                    catch hep_client:send_hep(Response, Net1IP, State#state.sip_port, format_ip(DestIP), DestPort),
                    ok;
                {error, Reason} ->
                    io_format("[IMS-CORE] ❌ Error sending UDP response to ~s:~p: ~p~n", [format_ip(DestIP), DestPort, Reason]),
                    {error, Reason}
            end
    end.

generate_nonce() ->
    integer_to_list(erlang:unique_integer([positive])).

parse_auth_header(HeaderValue) ->
    CleanHeader = case string:prefix(string:trim(HeaderValue), "Digest ") of
                      nomatch ->
                          case string:prefix(string:trim(HeaderValue), "digest ") of
                              nomatch -> HeaderValue;
                              Rest1 -> Rest1
                          end;
                      Rest -> Rest
                  end,
    Tokens = string:split(CleanHeader, ",", all),
    lists:foldl(fun(Token, Map) ->
        Trimmed = string:trim(Token),
        case string:split(Trimmed, "=", leading) of
            [Key, Value] ->
                CleanKey = string:lowercase(string:trim(Key)),
                CleanValue = string:trim(Value, both, "\" "),
                maps:put(list_to_binary(CleanKey), list_to_binary(CleanValue), Map);
            _ -> Map
        end
    end, #{}, Tokens).

md5_hex(Data) ->
    Hash = crypto:hash(md5, Data),
    lists:flatten([io_lib:format("~2.16.0b", [X]) || <<X>> <= Hash]).

lookup_user_password(Username) ->
    case sys_config:load() of
        {ok, Config} ->
            Users = sys_config:get_users(Config),
            find_password(Users, Username);
        _ -> error
    end.

find_password([], _) -> error;
find_password([UserMap | Rest], Username) ->
    case binary_to_list(maps:get(<<"username">>, UserMap, <<"">>)) == Username of
        true -> {ok, binary_to_list(maps:get(<<"password">>, UserMap, <<"">>))};
        false -> find_password(Rest, Username)
    end.

%% Downlink routing helper using Path
forward_down_to_pcscf(Msg, State) ->
    ToUser = case sip_parser:get_header(Msg, "to") of
                 undefined -> undefined;
                 ToVal -> sip_parser:extract_username(ToVal)
             end,
    FromUser = case sip_parser:get_header(Msg, "from") of
                   undefined -> undefined;
                   FromVal -> sip_parser:extract_username(FromVal)
               end,
    TargetUser = case lookup_scscf_path(ToUser) of
                     {ok, _} -> ToUser;
                     error -> FromUser
                 end,
    case lookup_scscf_path(TargetUser) of
        {ok, Path} ->
            {PathHost, PathPort} = sip_parser:parse_sip_uri(Path),
            forward_sip(Msg, PathHost, PathPort, State);
        error ->
            {PcscfHost, PcscfPort} = sys_config:get_address("pcscf"),
            forward_sip(Msg, PcscfHost, PcscfPort, State)
    end.

lookup_scscf_path(undefined) -> error;
lookup_scscf_path(Username) ->
    case ets:lookup(scscf_contacts, Username) of
        [{Username, authenticated, PathVal, ExpiryTime}] when PathVal =/= undefined ->
            check_expiry(Username, PathVal, ExpiryTime);
        _ ->
            case catch redis_client:query([<<"GET">>, <<"scscf:contact:", (list_to_binary(Username))/binary>>]) of
                {ok, Bin} when is_binary(Bin) ->
                    case catch binary_to_term(base64:decode(Bin)) of
                        {Username, authenticated, PathVal, ExpiryTime} = Record when PathVal =/= undefined ->
                            ets:insert(scscf_contacts, Record),
                            check_expiry(Username, PathVal, ExpiryTime);
                        _ -> error
                    end;
                _ -> error
            end
    end.

check_expiry(Username, PathVal, ExpiryTime) ->
    CurrentTime = erlang:system_time(second),
    case CurrentTime < ExpiryTime of
        true -> {ok, PathVal};
        false ->
            ets:delete(scscf_contacts, Username),
            io_format("[S-CSCF] ⚠️ Contact ~s has expired (Current: ~p, Expiry: ~p), deleting from registry.~n", [Username, CurrentTime, ExpiryTime]),
            error
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
    CallID = case string:str(CleanMsg, "CallID '") of
        0 -> "";
        Idx ->
            RestCall = string:sub_string(CleanMsg, Idx + 8),
            case string:split(RestCall, "'") of
                [C, _] -> C;
                _ -> ""
            end
    end,
    logger_json:log_msg(Level, Module, CallID, FinalMsg).

get_first_line(Msg) ->
    case string:split(Msg, "\n") of
        [Line | _] -> string:trim(Line);
        _ -> ""
    end.

format_ip(IP) when is_tuple(IP) -> inet:ntoa(IP);
format_ip(IP) -> IP.

%% =============================================================================
%% TCP Listener
%% =============================================================================
start_tcp_listener(Port) ->
    case gen_tcp:listen(Port, [binary, {active, false}, {reuseaddr, true}, {packet, raw}, inet]) of
        {ok, LSock} ->
            io_format("[IMS-CORE] ✅ TCP listening on port ~p~n", [Port]),
            accept_loop(LSock);
        {error, Reason} ->
            io_format("[IMS-CORE] ❌ TCP listen failed on port ~p: ~p~n", [Port, Reason])
    end.

accept_loop(LSock) ->
    case gen_tcp:accept(LSock) of
        {ok, CSock} ->
            spawn(fun() -> tcp_handler(CSock) end),
            accept_loop(LSock);
        _ ->
            accept_loop(LSock)
    end.

tcp_handler(CSock) ->
    Peer = case inet:peername(CSock) of
        {ok, {PeerIP, PeerPort}} -> 
            ets:insert(tcp_sockets, {{PeerIP, PeerPort}, CSock}),
            {PeerIP, PeerPort};
        _ -> undefined
    end,
    handle_tcp_data(CSock, <<>>),
    if Peer =/= undefined -> ets:delete(tcp_sockets, Peer); true -> ok end.

handle_tcp_data(CSock, Buffer) ->
    case gen_tcp:recv(CSock, 0, 10000) of
        {ok, Data} ->
            NewBuffer = <<Buffer/binary, Data/binary>>,
            process_buffer(CSock, NewBuffer);
        {error, _} ->
            gen_tcp:close(CSock)
    end.

process_buffer(CSock, Buffer) ->
    case binary:match(Buffer, <<"\r\n\r\n">>) of
        nomatch ->
            handle_tcp_data(CSock, Buffer);
        {Pos, 4} ->
            Headers = binary:part(Buffer, 0, Pos),
            CLen = extract_content_length(binary_to_list(Headers)),
            TotalLen = Pos + 4 + CLen,
            if byte_size(Buffer) >= TotalLen ->
                 Msg = binary:part(Buffer, 0, TotalLen),
                 {ok, {PeerIP, PeerPort}} = inet:peername(CSock),
                 gen_server:cast(?MODULE, {tcp_msg, binary_to_list(Msg), PeerIP, PeerPort}),
                 Rest = binary:part(Buffer, TotalLen, byte_size(Buffer) - TotalLen),
                 if byte_size(Rest) > 0 ->
                      process_buffer(CSock, Rest);
                    true ->
                      handle_tcp_data(CSock, <<>>)
                 end;
               true ->
                 handle_tcp_data(CSock, Buffer)
            end
    end.

extract_content_length(HeadersStr) ->
    Lines = string:tokens(HeadersStr, "\r\n"),
    lists:foldl(fun(Line, Acc) ->
        LowerLine = string:to_lower(Line),
        case string:prefix(LowerLine, "content-length:") of
            nomatch -> Acc;
            RestStr ->
                case string:to_integer(string:trim(RestStr)) of
                    {N, _} -> N;
                    _ -> Acc
                end
        end
    end, 0, Lines).


