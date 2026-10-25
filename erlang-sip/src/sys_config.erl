-module(sys_config).
-export([load/0, get_role/0, get_address/1, get_users/1, get_domain/0, resolve_host/1, get_net1_ip/0, get_eth0_ip/0, get_tailscale0_ip/0, resolve_ips_cached/1]).

load() ->
    catch inet_db:set_cache_size(0),
    case catch ets:lookup(sys_config_cache, config) of
        [{config, Map}] ->
            {ok, Map};
        _ ->
            Path = "/app/config/sys_config.json",
            case file:read_file(Path) of
                {ok, Bin} ->
                    Map = jiffy:decode(Bin, [return_maps]),
                    catch ets:insert(sys_config_cache, {config, Map}),
                    {ok, Map};
                {error, Reason} ->
                    io:format("[CONFIG] ⚠️ Failed to read config ~s: ~p, trying local path~n", [Path, Reason]),
                    %% Try local path for dev/test environment
                    case file:read_file("config/sys_config.json") of
                        {ok, Bin2} ->
                            Map2 = jiffy:decode(Bin2, [return_maps]),
                            catch ets:insert(sys_config_cache, {config, Map2}),
                            {ok, Map2};
                        {error, R2} -> {error, R2}
                    end
            end
    end.

get_role() ->
    case os:getenv("NODE_ROLE") of
        false -> "scscf"; %% fallback
        Role -> Role
    end.

%% Returns {Host, Port} for a given service name (binary or string)
get_address("pcscf") ->
    {"pcscf-service.ims.mnc001.mcc452.3gppnetwork.org", 5060};
get_address(<<"pcscf">>) ->
    {"pcscf-service.ims.mnc001.mcc452.3gppnetwork.org", 5060};
get_address(Service) ->
    case load() of
        {ok, Config} ->
            IMS = maps:get(<<"ims_core">>, Config, #{}),
            Services = maps:get(<<"services">>, Config, #{}),
            BinaryKey = list_to_binary(Service),
            case maps:find(BinaryKey, IMS) of
                {ok, Map} ->
                    Port = maps:get(<<"sip_port">>, Map, maps:get(<<"port">>, Map)),
                    {binary_to_list(maps:get(<<"host">>, Map)), Port};
                error ->
                    case maps:find(BinaryKey, Services) of
                        {ok, Map} ->
                            Port = maps:get(<<"sip_port">>, Map, maps:get(<<"port">>, Map)),
                            {binary_to_list(maps:get(<<"host">>, Map)), Port};
                        error ->
                            {error, not_found}
                    end
            end;
        _ ->
            {error, config_load_failed}
    end.

get_users(Config) ->
    maps:get(<<"users">>, Config, []).

get_domain() ->
    case load() of
        {ok, Config} ->
            Domains = maps:get(<<"domains">>, Config, #{}),
            binary_to_list(maps:get(<<"ims">>, Domains, <<"ims.mnc001.mcc452.3gppnetwork.org">>));
        _ -> "ims.mnc001.mcc452.3gppnetwork.org"
    end.

%% Resolves a host string/binary to a list of IP strings
resolve_host(Host) when is_binary(Host) ->
    resolve_host(binary_to_list(Host));
resolve_host(Host) ->
    case inet:getaddrs(Host, inet) of
        {ok, IPs} ->
            %% Sort IPs to ensure consistency across nodes
            SortedIPs = lists:sort(IPs),
            [inet:ntoa(IP) || IP <- SortedIPs];
        _ ->
            [Host]
    end.

%% Resolves a host to IP tuples with 10 seconds cache in ETS
resolve_ips_cached(Host) when is_binary(Host) ->
    resolve_ips_cached(binary_to_list(Host));
resolve_ips_cached(Host) ->
    Now = erlang:system_time(second),
    case catch ets:lookup(sys_config_cache, {dns, Host}) of
        [{{dns, Host}, IPs, ExpireTime}] when Now < ExpireTime ->
            IPs;
        _ ->
            IPs = case inet:getaddrs(Host, inet) of
                      {ok, Addrs} -> Addrs;
                      _ -> []
                  end,
            catch ets:insert(sys_config_cache, {{dns, Host}, IPs, Now + 10}),
            IPs
    end.

get_net1_ip() ->
    get_eth0_ip().

get_eth0_ip() ->
    case catch ets:lookup(sys_config_cache, eth0_ip) of
        [{eth0_ip, IP}] -> IP;
        _ ->
            IP = case inet:getifaddrs() of
                     {ok, IfList} ->
                          case lists:keyfind("eth0", 1, IfList) of
                              {"eth0", Opts} ->
                                  extract_ipv4(Opts);
                              _ ->
                                  find_first_active_ip(IfList)
                          end;
                     _ -> "127.0.0.1"
                 end,
            catch ets:insert(sys_config_cache, {eth0_ip, IP}),
            IP
    end.

get_tailscale0_ip() ->
    case catch ets:lookup(sys_config_cache, tailscale0_ip) of
        [{tailscale0_ip, IP}] -> IP;
        _ ->
            IP = case inet:getifaddrs() of
                     {ok, IfList} ->
                          case lists:keyfind("tailscale0", 1, IfList) of
                              {"tailscale0", Opts} ->
                                  extract_ipv4(Opts);
                              _ ->
                                  "127.0.0.1"
                          end;
                     _ -> "127.0.0.1"
                 end,
            catch ets:insert(sys_config_cache, {tailscale0_ip, IP}),
            IP
    end.

extract_ipv4(Opts) ->
    AddrList = [Addr || {addr, Addr} <- Opts, tuple_size(Addr) =:= 4],
    case AddrList of
        [Addr | _] -> inet:ntoa(Addr);
        _ -> "127.0.0.1"
    end.

find_first_active_ip([]) -> "127.0.0.1";
find_first_active_ip([{Name, Opts} | Tail]) ->
    case Name of
        "lo" -> find_first_active_ip(Tail);
        _ ->
            AddrList = [Addr || {addr, Addr} <- Opts, tuple_size(Addr) =:= 4],
            case AddrList of
                [Addr | _] -> inet:ntoa(Addr);
                _ -> find_first_active_ip(Tail)
            end
    end.
