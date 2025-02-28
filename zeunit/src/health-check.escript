#!/usr/bin/env escript
%% -*- erlang -*-
%%! -dist_listen false -hidden -kernel dist_auto_connect never

-include_lib("escript_utils.hrl").

main(Args) ->
    Options = init(Args),
    Node = get_node(Options),
    try
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
