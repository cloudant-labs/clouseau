-module(test_util).
-export([
    spawn_echo/1,
    wait_healthy/0
]).

-include("zeunit.hrl").

spawn_echo(Name) ->
    {ok, Pid} = gen_server:call({init, ?NodeZ}, {spawn, echo, Name}),
    Pid.

wait_healthy() ->
    is_binary(util:check_service(?NodeZ)).