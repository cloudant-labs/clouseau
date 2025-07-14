-module(util).
-export([a2l/1, l2a/1, b2l/1, l2b/1, b2t/1, t2b/1, to_binary/1]).
-export([call/2]).
-export([get_value/2, get_value/3]).
-export([seconds/1, receive_msg/0, receive_msg/1]).
-export([receive_pong/1, receive_pong/2]).
-export([
    check_ping/1, check_ping/2,
    check_service/1, check_service/2,
    wait_value/3,
    race/2,
    retry/2, retry/3,
    concurrent_retry/2, concurrent_retry/4
]).
-export([rand_char/1]).

-define(ALPHABET_SIZE, 26).

-define(TIMEOUT_IN_MS, 3000).
-define(PING_TIMEOUT_IN_MS, 3000).
-define(SERVICE_CHECK_ATTEMPTS, 30).
-define(SERVICE_CHECK_WAITTIME_IN_MS, 1500).
-define(RETRY_DELAY, 300).

a2l(V) -> atom_to_list(V).
l2a(V) -> list_to_atom(V).

b2l(V) -> binary_to_list(V).
l2b(V) -> list_to_binary(V).

b2t(V) -> binary_to_term(V).
t2b(V) -> term_to_binary(V).

to_binary(V) when is_binary(V) ->
    V;
to_binary(V) when is_list(V) ->
    try
        l2b(V)
    catch
        _:_ ->
            l2b(io_lib:format("~p", [V]))
    end;
to_binary(V) when is_atom(V) ->
    l2b(a2l(V));
to_binary(V) ->
    l2b(io_lib:format("~p", [V])).

call(ServerRef, Request) ->
    case catch gen_call(ServerRef, '$gen_call', Request) of
        {ok, Res} ->
            Res;
        {'EXIT', Reason} ->
            exit({Reason, {?MODULE, call, [ServerRef, Request]}})
    end.

gen_call(Process, Label, Request) ->
    Mref = erlang:monitor(process, Process),
    Process ! {Label, {self(), Mref}, Request},
    receive
        {Mref, Reply} ->
            erlang:demonitor(Mref, [flush]),
            {ok, Reply};
        {'DOWN', Mref, _, _, Reason} ->
            exit(Reason)
    end.

get_value(Key, List) ->
    get_value(Key, List, undefined).

get_value(Key, List, Default) ->
    case lists:keysearch(Key, 1, List) of
        {value, {K, Value}} when K =:= Key ->
            Value;
        false ->
            Default
    end.

seconds(N) when is_integer(N) ->
    N * 1000.

receive_msg() ->
    receive_msg(?TIMEOUT_IN_MS).

receive_msg(TimeoutInMs) ->
    receive
        Msg -> Msg
    after TimeoutInMs ->
        {error, timeout}
    end.

receive_pong(Ref) ->
    receive_pong(Ref, ?TIMEOUT_IN_MS).

receive_pong(Ref, TimeoutInMs) ->
    receive
        {pong, Ref} -> pong;
        _ -> false
    after TimeoutInMs ->
        {error, timeout}
    end.

check_ping(Node) -> check_ping(Node, ?PING_TIMEOUT_IN_MS).

check_ping(Node, TimeoutInMs) when is_atom(Node) ->
    wait_value(fun() -> net_adm:ping(Node) end, pong, TimeoutInMs).

check_service(Node) ->
    check_service(Node, ?SERVICE_CHECK_ATTEMPTS).

check_service(Node, RetriesN) when is_atom(Node) ->
    concurrent_retry(RetriesN, fun() ->
        try gen_server:call({main, Node}, version, ?SERVICE_CHECK_WAITTIME_IN_MS) of
            timeout ->
                wait;
            {ok, Version} ->
                Version
        catch
            _:_ ->
                wait
        end
    end).

concurrent_retry(RetriesN, Fun) ->
    concurrent_retry(RetriesN, ?RETRY_DELAY, ?TIMEOUT_IN_MS, Fun).

concurrent_retry(RetriesN, RetryDelay, Timeout, Fun) ->
    Funs = lists:map(
        fun(Idx) ->
            fun() ->
                timer:sleep(RetryDelay * Idx),
                Fun()
            end
        end,
        lists:seq(1, RetriesN)
    ),
    race(Funs, Timeout).

race(Funs, Timeout) ->
    ResultRef = make_ref(),
    Self = self(),
    lists:foreach(
        fun(F) ->
            spawn(
                fun() ->
                    case F() of
                        wait ->
                            wait;
                        Result ->
                            Self ! {ResultRef, Result}
                    end
                end
            )
        end,
        Funs
    ),
    receive
        {ResultRef, Result} -> Result
    after Timeout ->
        timeout
    end.

retry(Fun, Times) ->
    retry(Fun, Times, ?RETRY_DELAY).

retry(_Fun, Times, _DelayInMs) when Times =< 0 ->
    timeout;
retry(Fun, Times, DelayInMs) ->
    case Fun() of
        wait ->
            ok = timer:sleep(DelayInMs),
            retry(Fun, Times - 1, DelayInMs);
        Else ->
            Else
    end.

wait_value(Fun, Value, TimeoutInMs) ->
    wait(
        fun() ->
            case Fun() of
                Value -> Value;
                _ -> wait
            end
        end,
        TimeoutInMs
    ).

now_us() ->
    {MegaSecs, Secs, MicroSecs} = os:timestamp(),
    (MegaSecs * 1000000 + Secs) * 1000000 + MicroSecs.

wait(Fun, TimeoutInMs) ->
    Now = now_us(),
    wait(Fun, TimeoutInMs * 1000, ?RETRY_DELAY, Now, Now).

wait(_Fun, TimeoutInUs, _DelayInMs, Started, Prev) when Prev - Started > TimeoutInUs ->
    timeout;
wait(Fun, TimeoutInUs, DelayInMs, Started, _Prev) ->
    case Fun() of
        wait ->
            ok = timer:sleep(DelayInMs),
            wait(Fun, TimeoutInUs, DelayInMs, Started, now_us());
        Else ->
            Else
    end.

rand_char(N) ->
    rand_char(N, []).

rand_char(0, Acc) ->
    Acc;
rand_char(N, Acc) ->
    rand_char(N - 1, [rand:uniform(?ALPHABET_SIZE) - 1 + $a | Acc]).
