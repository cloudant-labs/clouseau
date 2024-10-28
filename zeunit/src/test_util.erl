-module(test_util).
-export([
    spawn_echo/1,
    tempdb/0,
    wait_healthy/0
]).

-include("zeunit.hrl").

-define(ALPHABET_SIZE, 26).

rand_char(N) ->
    rand_char(N, []).

rand_char(0, Acc) ->
    Acc;
rand_char(N, Acc) ->
    rand_char(N - 1, [rand:uniform(?ALPHABET_SIZE) - 1 + $a | Acc]).

tempdb() ->
    iolist_to_binary(["eunit-test-db-", rand_char(10)]).

spawn_echo(Name) ->
    {ok, Pid} = gen_server:call({init, ?NodeZ}, {spawn, echo, Name}),
    Pid.

wait_healthy() ->
    is_binary(util:check_service(?NodeZ)).
