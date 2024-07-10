#!/usr/bin/env escript
%% -*- erlang -*-
%%! -name test@127.0.0.1

-define(NAME, "clouseau1").
-define(HOST, "127.0.0.1").

-define(ANSI_COLORED, "\x1b[").
-define(ANSI_RESET, "\x1b[0m").
-define(ANSI_RED, "31").
-define(ANSI_GREEN, "32").
-define(ANSI_YELLOW, "33").

-define(PING_TIMEOUT_IN_MS, 10000).
-define(SERVICE_TIMEOUT_IN_MS, 15000).

main(Args) ->
    Options = parse_args(Args, #{name => undefined, cookie => undefined, other => []}),
    Node = get_node(Options),
    set_custom_cookie(Options),
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
            usage("Please check `node_name` and `cookie` in Makefile and app.conf"),
            usage(" node_name : " ++ atom_to_list(Node)),
            usage(" cookie    : " ++ atom_to_list(erlang:get_cookie())),
            usage("Use specified node name and cookie:"),
            usage("$ ./health-check.escript -name clouseau1 -setcookie cookie"),
            usage("Use cookie from `.erlang.cookie`:"),
            usage("$ ./health-check.escript -name clouseau1"),
            halt(1)
    end.

parse_args([], Options) ->
    Options;
parse_args(["-name", Name | Rest], Options) ->
    parse_args(Rest, Options#{name => Name});
parse_args(["-setcookie", Cookie | Rest], Options) ->
    parse_args(Rest, Options#{cookie => Cookie});
parse_args([Other | Rest], Options) ->
    parse_args(Rest, Options#{other => [Other | maps:get(other, Options)]}).

get_node(Options) ->
    Name =
        case maps:get(name, Options) of
            undefined -> ?NAME;
            Other -> Other
        end,
    list_to_atom(Name ++ "@" ++ ?HOST).

set_custom_cookie(Options) ->
    case maps:get(cookie, Options) of
        undefined -> ok;
        Cookie -> erlang:set_cookie(node(), list_to_atom(Cookie))
    end.

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

usage(Text) ->
    log_colored(?ANSI_YELLOW, usage, Text).

log_colored(Color, Status, Text) ->
    io:fwrite("[~s~s~s] ~s~n", [?ANSI_COLORED ++ Color ++ "m", Status, ?ANSI_RESET, Text]).
