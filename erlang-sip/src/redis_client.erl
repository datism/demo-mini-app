-module(redis_client).
-behaviour(gen_server).

-export([start_link/0, query/1, query/2]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2, code_change/3]).

-record(state, {
    socket = undefined,
    master_addr = undefined
}).

start_link() ->
    gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

query(Args) ->
    query(Args, 5000).
query(Args, Timeout) ->
    gen_server:call(?MODULE, {query, Args}, Timeout).

init([]) ->
    State = reconnect(#state{}),
    {ok, State}.

handle_call({query, Args}, _From, State) ->
    case get_connected_socket(State) of
        {ok, Socket, NewState} ->
            Cmd = format_cmd(Args),
            case gen_tcp:send(Socket, Cmd) of
                ok ->
                    case recv_resp(Socket) of
                        {ok, Resp} ->
                            {reply, {ok, Resp}, NewState};
                        {error, Reason} ->
                            io:format("[REDIS] ❌ Recv error: ~p, closing socket~n", [Reason]),
                            gen_tcp:close(Socket),
                            {reply, {error, Reason}, NewState#state{socket = undefined}}
                    end;
                {error, Reason} ->
                    io:format("[REDIS] ❌ Send error: ~p, closing socket~n", [Reason]),
                    gen_tcp:close(Socket),
                    {reply, {error, Reason}, NewState#state{socket = undefined}}
            end;
        {error, Reason} ->
            {reply, {error, Reason}, State}
    end;
handle_call(_Request, _From, State) ->
    {reply, ok, State}.

handle_cast(_Msg, State) -> {noreply, State}.
handle_info(_Info, State) -> {noreply, State}.
terminate(_Reason, State) ->
    case State#state.socket of
        undefined -> ok;
        S -> gen_tcp:close(S)
    end,
    ok.
code_change(_OldVsn, State, _Extra) -> {ok, State}.

%% RESP Formatting & Parsing Helpers
format_cmd(Args) ->
    Count = length(Args),
    Header = io_lib:format("*~B\r\n", [Count]),
    Payload = [
        begin
            Bin = if is_binary(A) -> A;
                     is_list(A) -> list_to_binary(A);
                     is_atom(A) -> atom_to_binary(A, utf8);
                     is_integer(A) -> integer_to_binary(A);
                     true -> A
                  end,
            Len = byte_size(Bin),
            io_lib:format("$~B\r\n~s\r\n", [Len, Bin])
        end || A <- Args
    ],
    list_to_binary([Header, Payload]).

recv_resp(Socket) ->
    case gen_tcp:recv(Socket, 0, 5000) of
        {ok, Bin} ->
            case parse_resp(Bin) of
                {ok, Val, _} -> {ok, Val};
                {nil, _} -> {ok, nil};
                {error, Err} -> {error, Err}
            end;
        {error, Reason} -> {error, Reason}
    end.

parse_resp(<<"+", Rest/binary>>) ->
    {ok, Val, Rest2} = read_until_crlf(Rest),
    {ok, Val, Rest2};
parse_resp(<<"-", Rest/binary>>) ->
    {ok, Err, Rest2} = read_until_crlf(Rest),
    {error, Err};
parse_resp(<<":", Rest/binary>>) ->
    {ok, ValStr, Rest2} = read_until_crlf(Rest),
    {ok, binary_to_integer(ValStr), Rest2};
parse_resp(<<"$-1\r\n", Rest/binary>>) ->
    {nil, Rest};
parse_resp(<<"$", Rest/binary>>) ->
    {ok, LenStr, Rest2} = read_until_crlf(Rest),
    Len = binary_to_integer(LenStr),
    <<Val:Len/binary, "\r\n", Rest3/binary>> = Rest2,
    {ok, Val, Rest3};
parse_resp(<<"*-1\r\n", Rest/binary>>) ->
    {nil, Rest};
parse_resp(<<"*", Rest/binary>>) ->
    {ok, CountStr, Rest2} = read_until_crlf(Rest),
    Count = binary_to_integer(CountStr),
    parse_array(Count, Rest2, []).

parse_array(0, Rest, Acc) ->
    {ok, lists:reverse(Acc), Rest};
parse_array(N, Bin, Acc) ->
    case parse_resp(Bin) of
        {ok, Val, Rest} -> parse_array(N - 1, Rest, [Val | Acc]);
        {nil, Rest} -> parse_array(N - 1, Rest, [nil | Acc]);
        Err -> Err
    end.

read_until_crlf(Bin) ->
    case binary:split(Bin, <<"\r\n">>) of
        [Val, Rest] -> {ok, Val, Rest};
        _ -> {error, missing_crlf}
    end.

%% Connection Management
get_connected_socket(State) ->
    case State#state.socket of
        undefined ->
            NewState = reconnect(State),
            case NewState#state.socket of
                undefined -> {error, connection_failed};
                Socket -> {ok, Socket, NewState}
            end;
        Socket ->
            {ok, Socket, State}
    end.

reconnect(State) ->
    SentinelHost = os:getenv("REDIS_SENTINEL_HOST", "redis-sentinel-service.demo-mini-app.svc.cluster.local"),
    SentinelPort = list_to_integer(os:getenv("REDIS_SENTINEL_PORT", "26379")),
    MasterAddr = get_master_addr(SentinelHost, SentinelPort),
    io:format("[REDIS] Master addr discovered: ~p~n", [MasterAddr]),
    {Host, Port} = parse_addr(MasterAddr),
    case gen_tcp:connect(Host, Port, [binary, {active, false}, {reuseaddr, true}], 5000) of
        {ok, Socket} ->
            State#state{socket = Socket, master_addr = MasterAddr};
        {error, Reason} ->
            io:format("[REDIS] ❌ Failed to connect to Redis master ~p: ~p~n", [MasterAddr, Reason]),
            State#state{socket = undefined}
    end.

parse_addr(undefined) ->
    Host = os:getenv("REDIS_HOST", "redis-service.demo-mini-app.svc.cluster.local"),
    Port = list_to_integer(os:getenv("REDIS_PORT", "6379")),
    {Host, Port};
parse_addr(Addr) ->
    [HostBin, PortBin] = binary:split(Addr, <<":">>),
    {binary_to_list(HostBin), binary_to_integer(PortBin)}.

get_master_addr(Host, Port) ->
    case gen_tcp:connect(Host, Port, [binary, {active, false}], 2000) of
        {ok, Socket} ->
            Cmd = format_cmd([<<"SENTINEL">>, <<"get-master-addr-by-name">>, <<"mymaster">>]),
            ok = gen_tcp:send(Socket, Cmd),
            Res = case recv_resp(Socket) of
                {ok, [IP, PortStr]} ->
                    <<IP/binary, ":", PortStr/binary>>;
                _ -> undefined
            end,
            gen_tcp:close(Socket),
            Res;
        _ -> undefined
    end.
