-module(logger_json).
-export([info/2, info/3, error/2, error/3, warn/2, warn/3, debug/2, debug/3, log_msg/4]).

to_bin(Val) when is_binary(Val) -> Val;
to_bin(Val) when is_list(Val) ->
    case unicode:characters_to_binary(lists:flatten(Val)) of
        Bin when is_binary(Bin) -> Bin;
        _ -> list_to_binary(Val)
    end;
to_bin(Val) when is_atom(Val) -> atom_to_binary(Val, utf8);
to_bin(Val) ->
    case unicode:characters_to_binary(io_lib:format("~p", [Val])) of
        Bin when is_binary(Bin) -> Bin;
        _ -> <<>>
    end.

log_msg(Level, Module, CallID, Msg) ->
    MinLevel = case os:getenv("LOG_LEVEL") of
        false -> "INFO";
        "" -> "INFO";
        L -> string:uppercase(L)
    end,
    
    LevelMap = #{
        "DEBUG" => 1,
        "INFO" => 2,
        "WARN" => 3,
        "ERROR" => 4
    },
    
    LevelVal = maps:get(Level, LevelMap, 2),
    MinLevelVal = maps:get(MinLevel, LevelMap, 2),
    
    case LevelVal >= MinLevelVal of
        true ->
            PodName = case os:getenv("POD_NAME") of
                false -> os:getenv("HOSTNAME", "unknown");
                N -> N
            end,
            PodIP = case os:getenv("POD_IP") of
                false -> "unknown";
                IP -> IP
            end,
            NodeName = case os:getenv("NODE_NAME") of
                false -> atom_to_list(node());
                NN -> NN
            end,
            Service = case os:getenv("NODE_ROLE") of
                false -> "ims-core";
                R -> R
            end,
            
            {{Year, Month, Day}, {Hour, Min, Sec}} = calendar:universal_time(),
            {_, _, Micro} = os:timestamp(),
            Timestamp = io_lib:format("~4..0w-~2..0w-~2..0wT~2..0w:~2..0w:~2..0w.~6..0wZ",
                                      [Year, Month, Day, Hour, Min, Sec, Micro]),
                                      
            FlatMsg = to_bin(Msg),
                                      
            Map = #{
                <<"timestamp">> => to_bin(Timestamp),
                <<"level">> => to_bin(Level),
                <<"service">> => to_bin(Service),
                <<"module">> => to_bin(Module),
                <<"pod_name">> => to_bin(PodName),
                <<"pod_ip">> => to_bin(PodIP),
                <<"node_name">> => to_bin(NodeName),
                <<"message">> => FlatMsg
            },
            Map2 = case CallID of
                "" -> Map;
                undefined -> Map;
                _ -> Map#{<<"call_id">> => to_bin(CallID)}
            end,
            JSON = jiffy:encode(Map2),
            io:format("~s~n", [JSON]);
        false ->
            ok
    end.

info(Module, Msg) -> info(Module, "", Msg).
info(Module, CallID, Msg) -> log_msg("INFO", Module, CallID, Msg).

error(Module, Msg) -> error(Module, "", Msg).
error(Module, CallID, Msg) -> log_msg("ERROR", Module, CallID, Msg).

warn(Module, Msg) -> warn(Module, "", Msg).
warn(Module, CallID, Msg) -> log_msg("WARN", Module, CallID, Msg).

debug(Module, Msg) -> debug(Module, "", Msg).
debug(Module, CallID, Msg) -> log_msg("DEBUG", Module, CallID, Msg).
