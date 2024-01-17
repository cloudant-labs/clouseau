-module(util).
-export([a2l/1, l2a/1]).
-export([check/1, wait_value/3]).

-define(TIMEOUT, 5).

a2l(V) -> atom_to_list(V).

l2a(V) -> list_to_atom(V).

check(Node) when is_atom(Node) ->
    wait_value(fun() -> net_adm:ping(Node) end, pong, ?TIMEOUT);
check(Node) ->
    wait_value(fun() -> net_adm:ping(l2a(Node)) end, pong, ?TIMEOUT).

wait_value(Fun, Value, Timeout) ->
    wait(
        fun() ->
            case Fun() of
                Value -> Value;
                _ -> wait
            end
        end,
        Timeout * 1000
    ).

now_us() ->
    {MegaSecs, Secs, MicroSecs} = os:timestamp(),
    (MegaSecs * 1000000 + Secs) * 1000000 + MicroSecs.

wait(Fun, Timeout) ->
    Now = now_us(),
    wait(Fun, Timeout * 1000, 50, Now, Now).

wait(_Fun, Timeout, _Delay, Started, Prev) when Prev - Started > Timeout ->
    timeout;
wait(Fun, Timeout, Delay, Started, _Prev) ->
    case Fun() of
        wait ->
            ok = timer:sleep(Delay),
            wait(Fun, Timeout, Delay, Started, now_us());
        Else ->
            Else
    end.
