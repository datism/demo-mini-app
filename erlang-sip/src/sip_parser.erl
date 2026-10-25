%% =============================================================================
%% @doc SIP Parser — Parse và Build SIP messages
%%
%% Hỗ trợ các thao tác:
%%   - parse_method/1      : lấy phương thức SIP (REGISTER, INVITE, ...)
%%   - parse_register/1    : bóc tách info từ REGISTER
%%   - parse_invite/1      : bóc tách info từ INVITE (bao gồm SDP)
%%   - parse_call_id/1     : lấy Call-ID header
%%   - build_200_ok_register/1 : tạo 200 OK cho REGISTER
%%   - build_200_ok_bye/1      : tạo 200 OK cho BYE
%%   - build_100_trying/1      : tạo 100 Trying
%%   - build_error_response/3  : tạo error response (4xx, 5xx)
%%   - rebuild_invite_sdp/2    : thay thế SDP body trong INVITE
%% =============================================================================
-module(sip_parser).

-export([
    parse_method/1,
    parse_register/1,
    parse_invite/1,
    parse_call_id/1,
    parse_status_code/1,
    parse_cseq_method/1,
    extract_sdp_body_pub/1,
    get_header/2,
    extract_username/1,
    build_response/4,
    build_200_ok_register/2,
    build_200_ok_invite/2,
    build_200_ok_bye/1,
    build_100_trying/1,
    build_error_response/3,
    rebuild_invite_sdp/2,
    add_header/3,
    replace_header/3,
    parse_sip_uri/1,
    decrement_or_add_max_forwards/1,
    remove_top_via/1,
    add_via/3,
    generate_to_tag/0
]).

%% =============================================================================
%% Parse Helpers
%% =============================================================================

%% Lấy phương thức từ Request-Line: "INVITE sip:bob@example.com SIP/2.0"
parse_method(Msg) ->
    Lines = split_lines(Msg),
    case Lines of
        [RequestLine | _] ->
            Tokens = string:tokens(RequestLine, " "),
            case Tokens of
                [Method | _] ->
                    case string:prefix(Method, "SIP/") of
                        nomatch -> {ok, Method};
                        _ -> {ok, "RESPONSE"}
                    end;
                _ -> {error, invalid_request_line}
            end;
        _ -> {error, empty_message}
    end.

split_lines(Msg) ->
    Normalized = re:replace(Msg, "\r\n", "\n", [global, {return, list}]),
    string:split(Normalized, "\n", all).

%% Lấy giá trị của một header cụ thể (case-insensitive)
get_header(Msg, HeaderName) ->
    HeadersPart = extract_headers_part(Msg),
    Lines = split_lines(HeadersPart),
    LowerName = string:lowercase(HeaderName),
    find_header(Lines, LowerName).

find_header([], _) -> undefined;
find_header([Line | Rest], Name) ->
    case string:split(Line, ":", leading) of
        [HName, HValue] ->
            case string:lowercase(string:trim(HName)) =:= Name of
                true  -> string:trim(HValue);
                false -> find_header(Rest, Name)
            end;
        _ -> find_header(Rest, Name)
    end.

get_all_headers(Msg, HeaderName) ->
    HeadersPart = extract_headers_part(Msg),
    Lines = split_lines(HeadersPart),
    LowerName = string:lowercase(HeaderName),
    find_all_headers(Lines, LowerName, []).

find_all_headers([], _, Acc) -> lists:reverse(Acc);
find_all_headers([Line | Rest], Name, Acc) ->
    case string:split(Line, ":", leading) of
        [HName, HValue] ->
            case string:lowercase(string:trim(HName)) =:= Name of
                true  -> find_all_headers(Rest, Name, [string:trim(HValue) | Acc]);
                false -> find_all_headers(Rest, Name, Acc)
            end;
        _ -> find_all_headers(Rest, Name, Acc)
    end.

extract_username(Uri) ->
    Trimmed = string:trim(Uri),
    CleanUri = case string:find(Trimmed, "<") of
        nomatch -> Trimmed;
        FoundLt ->
            NoLt = lists:nthtail(1, FoundLt),
            case string:split(NoLt, ">") of
                [Inside | _] -> Inside;
                _ -> NoLt
            end
    end,
    NoSip = case string:prefix(CleanUri, "sip:") of
        nomatch ->
            case string:prefix(CleanUri, "SIP:") of
                nomatch -> CleanUri;
                R2_2 -> R2_2
            end;
        R2 -> R2
    end,
    case string:split(NoSip, "@") of
        [User | _] -> string:trim(User);
        _          -> NoSip
    end.

%% Bóc tách SDP body (phần sau \r\n\r\n hoặc \n\n)
extract_sdp_body(Msg) ->
    case string:split(Msg, "\r\n\r\n") of
        [_, Body] -> Body;
        [_] ->
            case string:split(Msg, "\n\n") of
                [_, Body2] -> Body2;
                _ -> ""
            end;
        List when is_list(List), List =/= [] -> lists:last(List);
        _ -> ""
    end.

%% Bóc tách SIP headers (phần trước \r\n\r\n)
extract_headers_part(Msg) ->
    case string:split(Msg, "\r\n\r\n") of
        [Headers | _] when Headers =/= Msg -> Headers;
        _ ->
            case string:split(Msg, "\n\n") of
                [Headers2 | _] -> Headers2;
                _             -> Msg
            end
    end.


%% =============================================================================
%% parse_register/1
%% Bóc tách thông tin từ SIP REGISTER
%% Return: {ok, #{username, expires}} | {error, Reason}
%% =============================================================================
parse_register(Msg) ->
    From = get_header(Msg, "from"),
    _Contact = get_header(Msg, "contact"),
    ExpiresStr = get_header(Msg, "expires"),

    case From of
        undefined -> {error, no_from_header};
        _ ->
            Username = extract_username(From),
            Expires = case ExpiresStr of
                undefined -> 3600;
                E ->
                    case string:to_integer(E) of
                        {N, _} when N > 0 -> N;
                        _ -> 3600
                    end
            end,
            {ok, #{username => Username, expires => Expires}}
    end.

%% =============================================================================
%% parse_invite/1
%% Bóc tách thông tin từ SIP INVITE
%% Return: {ok, #{call_id, from_user, to_user, sdp}} | {error, Reason}
%% =============================================================================
parse_invite(Msg) ->
    CallId   = get_header(Msg, "call-id"),
    From     = get_header(Msg, "from"),
    To       = get_header(Msg, "to"),
    SdpBody  = extract_sdp_body(Msg),

    case {CallId, From, To} of
        {undefined, _, _} -> {error, no_call_id};
        {_, undefined, _} -> {error, no_from};
        {_, _, undefined} -> {error, no_to};
        _ ->
            FromUser = extract_username(From),
            ToUser   = extract_username(To),
            {ok, #{
                call_id   => string:trim(CallId),
                from_user => FromUser,
                to_user   => ToUser,
                sdp       => SdpBody
            }}
    end.

%% =============================================================================
%% parse_call_id/1
%% =============================================================================
parse_call_id(Msg) ->
    case get_header(Msg, "call-id") of
        undefined -> {error, no_call_id};
        CallId    -> {ok, string:trim(CallId)}
    end.

%% =============================================================================
%% rebuild_invite_sdp/2
%% Thay thế SDP body trong INVITE bằng SDP mới
%% =============================================================================
rebuild_invite_sdp(OrigMsg, NewSdp) ->
    HeadersPart = extract_headers_part(OrigMsg),

    %% Cập nhật Content-Length
    NewLen = integer_to_list(length(NewSdp)),
    UpdatedHeaders = update_content_length(HeadersPart, NewLen),

    UpdatedHeaders ++ "\r\n\r\n" ++ NewSdp.

update_content_length(Headers, NewLen) ->
    Lines = split_lines(Headers),
    UpdatedLines = lists:map(fun(Line) ->
        case string:lowercase(string:slice(Line, 0, 14)) =:= "content-length" of
            true  -> "Content-Length: " ++ NewLen;
            false -> Line
        end
    end, Lines),
    string:join(UpdatedLines, "\r\n").

%% =============================================================================
%% Build Response Helpers
%% =============================================================================

%% Bóc tách Via, From, To, Call-ID, CSeq để dùng lại trong response
get_response_headers(Msg) ->
    #{
        via     => get_all_headers(Msg, "via"),
        from    => get_header(Msg, "from"),
        to      => get_header(Msg, "to"),
        call_id => get_header(Msg, "call-id"),
        cseq    => get_header(Msg, "cseq")
    }.

%% Build một SIP response cơ bản
%% RFC 3261 §8.2.6.2: UAS MUST add tag to To header in responses (except 100)
build_response(Msg, StatusCode, ReasonPhrase, ExtraHeaders) ->
    #{via := Via, from := From, to := To,
      call_id := CallId, cseq := CSeq} = get_response_headers(Msg),

    %% RFC 7989: Passthrough Session-ID if present in request
    SessionIdLine = case get_header(Msg, "session-id") of
        undefined -> "";
        SID -> "Session-ID: " ++ SID ++ "\r\n"
    end,

    ViaLine = case Via of
        [] -> "";
        List when is_list(List) -> 
            string:join(lists:map(fun(V) -> "Via: " ++ V end, List), "\r\n") ++ "\r\n";
        V -> "Via: " ++ V ++ "\r\n"
    end,
    FromLine    = case From    of undefined -> ""; F -> "From: " ++ F ++ "\r\n" end,
    ToLine      = case To of
        undefined -> "";
        T ->
            %% RFC 3261 §8.2.6.2: Add to-tag for non-100 responses
            case StatusCode > 100 of
                true ->
                    case string:find(T, ";tag=") of
                        nomatch ->
                            Tag = generate_to_tag(),
                            "To: " ++ T ++ ";tag=" ++ Tag ++ "\r\n";
                        _ ->
                            "To: " ++ T ++ "\r\n"
                    end;
                false ->
                    "To: " ++ T ++ "\r\n"
            end
    end,
    CallIdLine  = case CallId  of undefined -> ""; C -> "Call-ID: " ++ C ++ "\r\n" end,
    CSeqLine    = case CSeq    of undefined -> ""; S -> "CSeq: " ++ S ++ "\r\n" end,

    "SIP/2.0 " ++ integer_to_list(StatusCode) ++ " " ++ ReasonPhrase ++ "\r\n" ++
    ViaLine ++
    FromLine ++
    ToLine ++
    CallIdLine ++
    CSeqLine ++
    SessionIdLine ++
    "Content-Length: 0\r\n" ++
    ExtraHeaders ++
    "\r\n".

%% 200 OK cho REGISTER (kèm Service-Route)
build_200_ok_register(Msg, ExtraHeaders) ->
    ExpiresStr = case get_header(Msg, "expires") of
        undefined -> "3600";
        E -> E
    end,
    build_response(Msg, 200, "OK",
        "Contact: <sip:server@localhost>\r\n" ++
        "Expires: " ++ ExpiresStr ++ "\r\n" ++
        "Feature-Caps: *;+g.3gpp.datachannel\r\n" ++ ExtraHeaders).

%% Chèn một header vào SIP Message
add_header(Msg, Name, Value) ->
    case string:split(Msg, "\r\n\r\n") of
        [Headers, Body] ->
            Headers ++ "\r\n" ++ Name ++ ": " ++ Value ++ "\r\n\r\n" ++ Body;
        [Headers] ->
            Trimmed = string:trim(Headers, trailing, "\r\n"),
            Trimmed ++ "\r\n" ++ Name ++ ": " ++ Value ++ "\r\n\r\n"
    end.

replace_header(Msg, Name, Value) ->
    case string:split(Msg, "\r\n\r\n") of
        [Headers, Body] ->
            Lines = split_lines(Headers),
            LowerName = string:lowercase(Name),
            UpdatedLines = lists:map(fun(Line) ->
                case string:split(Line, ":", leading) of
                    [HName, _] ->
                        case string:lowercase(string:trim(HName)) =:= LowerName of
                            true -> Name ++ ": " ++ Value;
                            false -> Line
                        end;
                    _ -> Line
                end
            end, Lines),
            string:join(UpdatedLines, "\r\n") ++ "\r\n\r\n" ++ Body;
        [Headers] ->
            Lines = split_lines(Headers),
            LowerName = string:lowercase(Name),
            UpdatedLines = lists:map(fun(Line) ->
                case string:split(Line, ":", leading) of
                    [HName, _] ->
                        case string:lowercase(string:trim(HName)) =:= LowerName of
                            true -> Name ++ ": " ++ Value;
                            false -> Line
                        end;
                    _ -> Line
                end
            end, Lines),
            string:join(UpdatedLines, "\r\n") ++ "\r\n\r\n"
    end.

decrement_or_add_max_forwards(Msg) ->
    case get_header(Msg, "max-forwards") of
        undefined ->
            add_header(Msg, "Max-Forwards", "70");
        Val ->
            case string:to_integer(string:trim(Val)) of
                {Int, _} when Int > 0 ->
                    NewVal = integer_to_list(Int - 1),
                    replace_header(Msg, "Max-Forwards", NewVal);
                _ ->
                    replace_header(Msg, "Max-Forwards", "0")
            end
    end.

%% Phân giải SIP URI (lấy Host và Port)
parse_sip_uri(Uri) ->
    Trimmed = string:trim(Uri),
    NoLt = case string:prefix(Trimmed, "<") of
        nomatch -> Trimmed;
        R1 -> R1
    end,
    NoGt = case string:slice(NoLt, length(NoLt) - 1, 1) of
        ">" -> string:slice(NoLt, 0, length(NoLt) - 1);
        _ -> NoLt
    end,
    NoSip = case string:prefix(NoGt, "sip:") of
        nomatch ->
            case string:prefix(NoGt, "SIP:") of
                nomatch -> NoGt;
                R2 -> R2
            end;
        R3 -> R3
    end,
    NoParams = case string:split(NoSip, ";") of
        [HostPort | _] -> HostPort;
        _ -> NoSip
    end,
    case string:split(NoParams, ":") of
        [Host, PortStr] ->
            case string:to_integer(PortStr) of
                {Port, _} -> {Host, Port};
                _ -> {Host, 5060}
            end;
        _ -> {NoParams, 5060}
    end.

%% 200 OK cho INVITE (kèm theo SDP Answer)
%% RFC 3261 §8.2.6.2: Must add to-tag
build_200_ok_invite(Msg, SdpAnswer) ->
    #{via := Via, from := From, to := To,
      call_id := CallId, cseq := CSeq} = get_response_headers(Msg),

    %% RFC 7989: Passthrough Session-ID if present
    SessionIdLine = case get_header(Msg, "session-id") of
        undefined -> "";
        SID -> "Session-ID: " ++ SID ++ "\r\n"
    end,

    ViaLine     = case Via     of undefined -> ""; V -> "Via: " ++ V ++ "\r\n" end,
    FromLine    = case From    of undefined -> ""; F -> "From: " ++ F ++ "\r\n" end,
    ToLine      = case To of
        undefined -> "";
        T ->
            case string:find(T, ";tag=") of
                nomatch ->
                    Tag = generate_to_tag(),
                    "To: " ++ T ++ ";tag=" ++ Tag ++ "\r\n";
                _ ->
                    "To: " ++ T ++ "\r\n"
            end
    end,
    CallIdLine  = case CallId  of undefined -> ""; C -> "Call-ID: " ++ C ++ "\r\n" end,
    CSeqLine    = case CSeq    of undefined -> ""; S -> "CSeq: " ++ S ++ "\r\n" end,

    LenStr = integer_to_list(length(SdpAnswer)),

    "SIP/2.0 200 OK\r\n" ++
    ViaLine ++
    FromLine ++
    ToLine ++
    CallIdLine ++
    CSeqLine ++
    SessionIdLine ++
    "Content-Type: application/sdp\r\n" ++
    "Content-Length: " ++ LenStr ++ "\r\n" ++
    "\r\n" ++
    SdpAnswer.

%% 200 OK cho BYE
build_200_ok_bye(Msg) ->
    build_response(Msg, 200, "OK", "").

%% 100 Trying
build_100_trying(Msg) ->
    build_response(Msg, 100, "Trying", "").

%% Error response (4xx, 5xx, ...)
build_error_response(Msg, Code, Reason) ->
    build_response(Msg, Code, Reason, "").

%% =============================================================================
%% Response parsing helpers (dùng khi xử lý 200 OK từ B)
%% =============================================================================

%% parse_status_code/1
%% Lấy status code từ SIP response: "SIP/2.0 200 OK" → 200
parse_status_code(Msg) ->
    Lines = split_lines(Msg),
    case Lines of
        [StatusLine | _] ->
            Tokens = string:tokens(StatusLine, " "),
            case Tokens of
                [_Version, Code | _] ->
                    case string:to_integer(Code) of
                        {N, _} when N > 0 -> N;
                        _                 -> 0
                    end;
                _ -> 0
            end;
        _ -> 0
    end.

%% parse_cseq_method/1
%% Lấy phương thức từ CSeq header: "1 INVITE" → {ok, "INVITE"}
parse_cseq_method(Msg) ->
    case get_header(Msg, "cseq") of
        undefined -> {error, no_cseq};
        CSeqVal ->
            Tokens = string:tokens(CSeqVal, " "),
            case Tokens of
                [_, Method | _] -> {ok, Method};
                [Method]        -> {ok, Method};
                _               -> {error, invalid_cseq}
            end
    end.

%% extract_sdp_body_pub/1
%% Public wrapper để lấy SDP body từ bất kỳ SIP message
%% Được gọi bởi sip_server khi parse 200 OK từ B
extract_sdp_body_pub(Msg) ->
    extract_sdp_body(Msg).

remove_top_via(Msg) ->
    case string:split(Msg, "\r\n\r\n") of
        [Headers, Body] ->
            Lines = split_lines(Headers),
            NewLines = remove_first_via_line_or_value(Lines),
            string:join(NewLines, "\r\n") ++ "\r\n\r\n" ++ Body;
        [Headers] ->
            Lines = split_lines(Headers),
            NewLines = remove_first_via_line_or_value(Lines),
            string:join(NewLines, "\r\n") ++ "\r\n\r\n"
    end.

remove_first_via_line_or_value([]) -> [];
remove_first_via_line_or_value([Line | Rest]) ->
    case string:split(Line, ":", leading) of
        [HName, HValue] ->
            case string:lowercase(string:trim(HName)) =:= "via" of
                true ->
                    case string:split(HValue, ",") of
                        [_, SecondValue | MoreValues] ->
                            [HName ++ ":" ++ string:join([SecondValue | MoreValues], ",") | Rest];
                        _ ->
                            Rest
                    end;
                false ->
                    [Line | remove_first_via_line_or_value(Rest)]
            end;
        _ ->
            [Line | remove_first_via_line_or_value(Rest)]
    end.

%% =============================================================================
%% Via Insertion (RFC 3261 §16.6 Step 4)
%% =============================================================================

%% Thêm Via header cho proxy khi forward request
add_via(Msg, IP, Port) ->
    Branch = generate_branch(),
    ViaVal = "SIP/2.0/UDP " ++ IP ++ ":" ++ integer_to_list(Port) ++ ";branch=" ++ Branch,
    %% Insert Via ngay sau Request-Line (trước các Via headers hiện có)
    case string:split(Msg, "\r\n", leading) of
        [RequestLine, Rest] ->
            RequestLine ++ "\r\n" ++ "Via: " ++ ViaVal ++ "\r\n" ++ Rest;
        _ ->
            Msg
    end.

%% RFC 3261 §8.1.1.7: Branch must start with z9hG4bK (magic cookie)
generate_branch() ->
    Rand = integer_to_list(erlang:unique_integer([positive])),
    "z9hG4bK-" ++ Rand.

%% RFC 3261 §8.2.6.2: Generate random to-tag for UAS responses
generate_to_tag() ->
    integer_to_list(erlang:unique_integer([positive])).
