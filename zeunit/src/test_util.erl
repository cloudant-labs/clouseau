-module(test_util).
-export([
    spawn_echo/1,
    tempdb/0,
    random_atom/0,
    rand_delay_sec/1,
    rand_delay_ms/1,
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

rand_delay_sec(Max) ->
    timer:sleep(round(timer:seconds(rand:uniform(Max)))).

rand_delay_ms(Max) ->
    timer:sleep(round(rand:uniform(Max))).

tempdb() ->
    iolist_to_binary(["eunit-test-db-", rand_char(10)]).

random_atom() ->
    list_to_atom("eunit-atom-" ++ rand_char(10)).

spawn_echo(Name) ->
    {ok, Pid} = gen_server:call({init, ?NodeZ}, {spawn, echo, Name}),
    Pid.

wait_healthy() ->
    is_binary(util:check_service(?NodeZ)).
