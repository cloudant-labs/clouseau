-module(test_util).
-export([setup/0, teardown/1, spawn_echo/1]).

-include("zeunit.hrl").

spawn_echo(Name) ->
    {ok, Pid} = gen_server:call({init, ?NodeZ}, {spawn, echo, Name}),
    Pid.

setup() ->
    {ok, _} = net_kernel:start(?NodeT, #{name_domain => longnames}),
    true = erlang:set_cookie(?NodeT, ?COOKIE),
    pong = util:check_ping(?NodeZ),
    spawn_echo(echo).

teardown(Pid) ->
    exit(Pid, normal),
    ok = net_kernel:stop().
