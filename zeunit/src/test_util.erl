-module(test_util).
-export([setup/0, teardown/1]).

-include("zeunit.hrl").

setup() ->
    {ok, _} = net_kernel:start(?NodeT, #{name_domain => longnames}),
    true = erlang:set_cookie(?NodeT, ?COOKIE),
    pong = util:check_ping(?NodeZ),
    {ok, Pid} = gen_server:call({init, ?NodeZ}, {spawn, echo, echo}),
    Pid.

teardown(Pid) ->
    exit(Pid, normal),
    ok = net_kernel:stop().
