-module(echo_tests).
-include_lib("eunit/include/eunit.hrl").

-define(COOKIE, cookie).
-define(HOST, "127.0.0.1").
-define(NodeT, list_to_atom("node1@" ++ ?HOST)).
-define(NodeZ, list_to_atom("clouseau1@" ++ ?HOST)).

onMessage_test_() ->
    {
        "Test Echo Service",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            [
                fun t_canary/0,
                fun t_node/0,
                fun t_echo_success/0,
                fun t_echo_failure/0,
                fun t_call_echo/0,
                fun t_call_version/0
            ]
        }
    }.

t_canary() ->
    ?assert(true).

t_node() ->
    pong = util:check(?NodeZ).

t_echo_success() ->
    {coordinator, ?NodeZ} ! {echo, self(), erlang:system_time(microsecond), 1},
    {Symbol, Pid, _, _, _, Seq} = receive_msg(),
    ?assertEqual({Symbol, Pid, Seq}, {echo_reply, self(), 1}).

t_echo_failure() ->
    {coordinator, ?NodeZ} ! {echo, self(), unexpected_msg},
    ?assertEqual({error, timeout}, receive_msg()).

t_call_echo() ->
    ?assertEqual({echo, {}}, gen_server:call({coordinator, ?NodeZ}, {echo, {}})).

t_call_version() ->
    ?assertEqual(<<"0.1.0">>, gen_server:call({coordinator, ?NodeZ}, version)).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%
setup() ->
    {ok, _} = net_kernel:start(?NodeT, #{name_domain => longnames}),
    true = erlang:set_cookie(?NodeT, ?COOKIE),
    pong = util:check(?NodeZ).

teardown(_) ->
    ok = net_kernel:stop().

receive_msg() ->
    receive
        Msg -> Msg
    after 3000 ->
        {error, timeout}
    end.
