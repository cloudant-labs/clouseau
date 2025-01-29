-define(NAME, "clouseau1").
-define(HOST, "127.0.0.1").

-define(ANSI_COLORED, "\x1b[").
-define(ANSI_RESET, "\x1b[0m").
-define(ANSI_RED, "31").
-define(ANSI_GREEN, "32").
-define(ANSI_YELLOW, "33").

-define(PING_TIMEOUT_IN_MS, 10_000).

app_dir() ->
    filename:dirname(filename:dirname(escript:script_name())).

src_dir() ->
    app_dir() ++ "/src".

compile() ->
    Sources = filelib:wildcard(src_dir() ++ "/*.erl"),
    lists:foreach(
        fun(Item) ->
            compile(Item)
        end,
        Sources
    ),
    ok.

compile(ModFile) ->
    compile(ModFile, []).

compile(ModFile, Opts) ->
    case
        compile:file(ModFile, [binary, report, return_errors, {i, app_dir() ++ "/include"} | Opts])
    of
        {ok, ModName, Binary} ->
            case code:load_binary(ModName, [], Binary) of
                {module, ModName} ->
                    ok;
                {error, Reason} ->
                    nok(io_lib:format("~p~n", [Reason])),
                    halt(1)
            end;
        {error, Errors, _} ->
            nok(io_lib:format("~p~n", [Errors])),
            halt(1)
    end.

ok(Text) ->
    log_colored(?ANSI_GREEN, success, Text),
    ok.

nok(Text) ->
    log_colored(?ANSI_RED, failure, Text),
    nok.

usage(Text) ->
    log_colored(?ANSI_YELLOW, usage, Text).

log_colored(Color, Status, Text) ->
    io:fwrite("[~s~s~s] ~s~n", [?ANSI_COLORED ++ Color ++ "m", Status, ?ANSI_RESET, Text]).

'$parse_args'([], Options) ->
    Options;
'$parse_args'(["-name", Name | Rest], Options) ->
    '$parse_args'(Rest, Options#{name => Name});
'$parse_args'(["-setcookie", Cookie | Rest], Options) ->
    '$parse_args'(Rest, Options#{cookie => Cookie});
'$parse_args'([Other | Rest], Options) ->
    '$parse_args'(Rest, Options#{other => [Other | maps:get(other, Options)]}).

get_node(Options) ->
    Name =
        case maps:get(name, Options) of
            undefined -> ?NAME;
            Other -> Other
        end,
    list_to_atom(Name ++ "@" ++ ?HOST).

'$set_custom_cookie'(Options) ->
    case maps:get(cookie, Options) of
        undefined -> ok;
        Cookie -> erlang:set_cookie(node(), list_to_atom(Cookie))
    end.

check_service(Node) ->
    case util:check_service(Node) of
        Version when is_binary(Version) ->
            ok("Service is live");
        _ ->
            nok("Service unavailable")
    end.

check_ping(Node) ->
    case util:check_ping(Node, ?PING_TIMEOUT_IN_MS) of
        pong ->
            ok("Node is accessible");
        _ ->
            nok("Node is not responding")
    end.

init(Args) ->
    Options = '$parse_args'(Args, #{name => undefined, cookie => undefined, other => []}),
    '$set_custom_cookie'(Options),
    compile(),
    Options.
