#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name test@127.0.0.1 -setcookie cookie

-define(NAME, "clouseau1").
-define(HOST, "127.0.0.1").

-define(ANSI_COLORED, "\x1b[").
-define(ANSI_RESET, "\x1b[0m").
-define(ANSI_RED, "31").
-define(ANSI_GREEN, "32").

-define(PING_TIMEOUT_IN_MS, 10000).
-define(SERVICE_TIMEOUT_IN_MS, 10000).

main(Args) ->
    Node = get_node(Args),
    try
        CurDir = filename:dirname(escript:script_name()),
        Util = filename:join([CurDir, "util.erl"]),
        {ok, util} = compile:file(Util),
        ok = check_ping(Node),
        ok = check_service(Node),
        ok("Ziose Node Health Check")
    catch
        _:_ ->
            nok("Ziose Node Health Check FAILED"),
            halt(1)
    end.

get_node(Args) ->
    Name =
        case string:trim(Args) of
            "" -> ?NAME;
            Other -> Other
        end,
    list_to_atom(Name ++ "@" ++ ?HOST).

check_service(Node) ->
    case util:check_service(Node, ?SERVICE_TIMEOUT_IN_MS) of
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

ok(Text) ->
    log_colored(?ANSI_GREEN, success, Text),
    ok.

nok(Text) ->
    log_colored(?ANSI_RED, failure, Text),
    nok.

log_colored(Color, Status, Text) ->
    io:fwrite("[~s~s~s] ~s~n", [?ANSI_COLORED ++ Color ++ "m", Status, ?ANSI_RESET, Text]).
