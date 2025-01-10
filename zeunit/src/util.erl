-module(util).
-export([a2l/1, l2a/1, b2l/1, l2b/1, b2t/1, t2b/1, to_binary/1]).
-export([call/2]).
-export([get_value/2, get_value/3]).
-export([seconds/1, receive_msg/0, receive_msg/1]).
-export([
    check_ping/1, check_ping/2,
    check_service/1, check_service/2,
    wait_value/3
]).

-define(TIMEOUT_IN_MS, 3000).
-define(PING_TIMEOUT_IN_MS, 3000).
-define(SERVICE_TIMEOUT_IN_MS, 10000).

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

check_ping(Node) -> check_ping(Node, ?PING_TIMEOUT_IN_MS).

check_ping(Node, TimeoutInMs) when is_atom(Node) ->
    wait_value(fun() -> net_adm:ping(Node) end, pong, TimeoutInMs).

check_service(Node) ->
    check_service(Node, ?SERVICE_TIMEOUT_IN_MS).

check_service(Node, TimeoutInMs) when is_atom(Node) ->
    wait(
        fun() ->
            try gen_server:call({main, Node}, version) of
                timeout -> wait;
                {ok, Version} -> Version
            catch
                _:_ -> wait
            end
        end,
        TimeoutInMs
    ).

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
    wait(Fun, TimeoutInMs * 1000, 50, Now, Now).

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
