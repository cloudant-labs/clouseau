-module(clouseau_utils).

-feature(maybe_expr, enable).

-export([
    init/1,
    check_node_ping/1,
    concurrent_retry/2,
    random_string/1,
    wait/2,
    wait_value/3
]).

-include_lib("clouseau.hrl").

-define(ALPHABET_SIZE, 26).

-define(TIMEOUT_IN_MS, 3000).
-define(NODE_TIMEOUT_IN_MS, 60_000).
-define(PING_TIMEOUT_IN_MS, 10_000).
-define(RETRY_DELAY, 300).

set_custom_cookie(#clouseau_ctx{cookie = undefined}) ->
    ok;
set_custom_cookie(#clouseau_ctx{cookie = Cookie}) ->
    % elp:ignore atoms_exhaustion - Atom exhastion is not an issue for CLIs (invoked once)
    try erlang:set_cookie(node(), list_to_atom(Cookie)) of
        true ->
            ok
    catch
        error:function_clause ->
            {error, set_cookie}
    end.

concurrent_retry(RetriesN, Fun) ->
    concurrent_retry(RetriesN, ?RETRY_DELAY, ?TIMEOUT_IN_MS, Fun).

concurrent_retry(RetriesN, RetryDelay, Timeout, Fun) ->
    Funs = lists:map(
        fun(Idx) when is_integer(Idx) ->
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
        fun(F) when is_function(F, 0) ->
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

check_node_ping(Node) ->
    case check_node_ping(Node, ?PING_TIMEOUT_IN_MS) of
        pong ->
            clouseau_io:ok("Node is accessible");
        _ ->
            clouseau_io:nok("Node is not responding")
    end.

check_node_ping(Node, TimeoutInMs) when is_atom(Node) ->
    wait_value(fun() -> net_adm:ping(Node) end, pong, TimeoutInMs).

enable_networking(#clouseau_ctx{remote = RemoteNode, local = LocalNode} = Args) ->
    maybe
        {ok, _} ?= net_kernel:start(LocalNode, #{name_domain => longnames}),
        ok ?= set_custom_cookie(Args),
        ok ?= connect_node(RemoteNode)
    else
        Error ->
            Error
    end.

connect_node(RemoteNode) ->
    case
        wait_value(
            fun() ->
                net_kernel:connect_node(RemoteNode)
            end,
            true,
            ?NODE_TIMEOUT_IN_MS
        )
    of
        true ->
            ok;
        timeout ->
            {error, {connect_node, RemoteNode}}
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

-spec init(#clouseau_ctx{}) ->
    ok | {error, Reason :: term()}.
init(#clouseau_ctx{} = Args) ->
    enable_networking(Args).

random_string(N) ->
    rand_char(N, []).

rand_char(0, Acc) ->
    Acc;
rand_char(N, Acc) ->
    rand_char(N - 1, [rand:uniform(?ALPHABET_SIZE) - 1 + $a | Acc]).
