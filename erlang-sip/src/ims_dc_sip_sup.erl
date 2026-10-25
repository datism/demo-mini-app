%% @doc OTP Supervisor for IMS DC SIP Server.
-module(ims_dc_sip_sup).
-behaviour(supervisor).

-export([start_link/0]).
-export([init/1]).

start_link() ->
    supervisor:start_link({local, ?MODULE}, ?MODULE, []).

init([]) ->
    io:format("[SUP] Starting supervisor...~n"),

    SupFlags = #{
        strategy  => one_for_one,
        intensity => 5,
        period    => 10
    },

    Children = [
        %% Redis Client Process
        #{
            id      => redis_client,
            start   => {redis_client, start_link, []},
            restart => permanent,
            type    => worker,
            modules => [redis_client]
        },
        %% SIP Server process — lắng nghe UDP 5060
        #{
            id      => sip_server,
            start   => {sip_server, start_link, []},
            restart => permanent,
            type    => worker,
            modules => [sip_server]
        }
    ],

    {ok, {SupFlags, Children}}.
