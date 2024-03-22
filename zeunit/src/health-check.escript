#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name test@127.0.0.1 -setcookie cookie

-define(NAME, "clouseau1").
-define(HOST, "127.0.0.1").

-define(ANSI_COLORED, "\x1b[").
-define(ANSI_RESET, "\x1b[0m").
-define(ANSI_RED, "31").
-define(ANSI_GREEN, "32").

-define(TIMEOUT_IN_MS, 30000).

main(Args) ->
    Node = get_node(Args),
    try
        CurDir = filename:dirname(escript:script_name()),
        Util = filename:join([CurDir, "util.erl"]),
        {ok, util} = compile:file(Util),
        pong = util:check(Node, ?TIMEOUT_IN_MS),
        log(success)
    catch
        _:_ ->
            log(failure),
            halt(1)
    end.

get_node(Args) ->
    Name =
        case string:trim(Args) of
            "" -> ?NAME;
            Other -> Other
        end,
    Name ++ "@" ++ ?HOST.

log(success) ->
    log_colored(?ANSI_GREEN, success, "Ziose Node Health Check");
log(failure) ->
    log_colored(?ANSI_RED, failure, "Ziose Node Health Check FAILED").

log_colored(Color, Status, Text) ->
    io:fwrite("[~s~s~s] ~s~n", [?ANSI_COLORED ++ Color ++ "m", Status, ?ANSI_RESET, Text]).
