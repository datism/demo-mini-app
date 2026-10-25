%% @doc OTP Application callback for IMS DC SIP Server.
-module(ims_dc_sip_app).
-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    io:format("[APP] Starting IMS DC SIP Server...~n"),
    catch ets:new(sys_config_cache, [named_table, set, public, {read_concurrency, true}]),
    sys_config:load(),
    ims_dc_sip_sup:start_link().

stop(_State) ->
    io:format("[APP] IMS DC SIP Server stopped.~n"),
    ok.
