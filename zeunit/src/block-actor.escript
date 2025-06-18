#!/usr/bin/env escript
%% -*- erlang -*-
%%! -dist_listen false -hidden -kernel dist_auto_connect never

-include_lib("escript_utils.hrl").

main(Args) ->
    Options = init(Args),
    BlockTimeInMs = parse_argument(Options),
    Node = get_node(Options),
    try
        ok = check_ping(Node),
        ok = check_service(Node),
        ok("Ziose Node Health Check"),
        {ok, Pid} = gen_server:call({init, Node}, {spawn, echo, 'block'}),
        Pid ! {block_for_ms, BlockTimeInMs},
        ok
    catch
        _:_ ->
            nok("Ziose Node Health Check FAILED"),
            usage("Please check `node_name` and `cookie` in Makefile and app.conf"),
            usage(" node_name : " ++ atom_to_list(Node)),
            usage(" cookie    : " ++ atom_to_list(erlang:get_cookie())),
            usage("Use specified node name and cookie:"),
            usage("$ ./block-actor.escript -name clouseau1 -setcookie cookie <BLOCK_TIME_IN_MS>"),
            usage("Use cookie from `.erlang.cookie`:"),
            usage("$ ./block-actor.escript -name clouseau1 <BLOCK_TIME_IN_MS>"),
            halt(1)
    end.

parse_argument(#{other := [BlockTimeStr]}) ->
    try
        list_to_integer(BlockTimeStr)
    catch
        _:_ ->
            nok("Expected integer value for <BLOCK_TIME_IN_MS>")
    end;
parse_argument(#{other := []}) ->
    nok("Missing mandatory positional argument <BLOCK_TIME_IN_MS>");
parse_argument(_) ->
    nok("Expected single mandatory positional argument <BLOCK_TIME_IN_MS>").
