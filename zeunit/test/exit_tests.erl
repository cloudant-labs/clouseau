-module(exit_tests).
-include("zeunit.hrl").

-define(TIMEOUT_IN_MS, 3000).

-define(FAIL(Reason),
    erlang:error(
        {fail, [
            {module, ?MODULE},
            {line, ?LINE},
            {reason, Reason}
        ]}
    )
).

exit_test_() ->
    {
        "Test exit handling",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            [
                fun t_exit_with_term_reason/0,
                fun t_exit_with_string_reason/0,
                fun t_exit_from_terminate_with_term_reason/0,
                fun t_exit_from_terminate_with_string_reason/0,
                fun t_throw_from_terminate_with_string_reason/0
            ]
        }
    }.

t_exit_with_term_reason() ->
    Pid = test_util:spawn_echo(?FUNCTION_NAME),
    Ref = monitor(process, Pid),
    Pid ! {exitWithReason, {fail, myReason}},
    ?assertMatch({'DOWN', Ref, process, Pid, {fail, myReason}}, util:receive_msg()).

t_exit_with_string_reason() ->
    Pid = test_util:spawn_echo(?FUNCTION_NAME),
    Ref = monitor(process, Pid),
    Pid ! {exitWithReason, "myReason"},
    %% Clouseau uses binary to encode strings
    ?assertMatch({'DOWN', Ref, process, Pid, <<"myReason">>}, util:receive_msg()).

t_exit_from_terminate_with_term_reason() ->
    Pid = test_util:spawn_echo(?FUNCTION_NAME),
    Ref = monitor(process, Pid),
    ?assertMatch(ok, gen_server:call(Pid, {setExitFromTerminate, [myOnTerminateReason]})),
    ?assertMatch({stop, myOnMessageReason}, gen_server:call(Pid, {stop, myOnMessageReason})),
    receive
        {'DOWN', Ref, process, Pid, myOnMessageReason} -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end,

    receive
        [myOnTerminateReason] -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end.

t_exit_from_terminate_with_string_reason() ->
    Pid = test_util:spawn_echo(?FUNCTION_NAME),
    Ref = monitor(process, Pid),
    ?assertMatch(ok, gen_server:call(Pid, {setExitFromTerminate, "myOnTerminateReason"})),
    ?assertMatch({stop, myOnMessageReason}, gen_server:call(Pid, {stop, myOnMessageReason})),
    receive
        {'DOWN', Ref, process, Pid, myOnMessageReason} -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end,

    receive
        <<"myOnTerminateReason">> -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end.

t_throw_from_terminate_with_string_reason() ->
    Pid = test_util:spawn_echo(?FUNCTION_NAME),
    Ref = monitor(process, Pid),
    ?assertMatch(ok, gen_server:call(Pid, {setThrowFromTerminate, "myOnTerminateReason"})),
    ?assertMatch({stop, myOnMessageReason}, gen_server:call(Pid, {stop, myOnMessageReason})),
    receive
        {'DOWN', Ref, process, Pid, myOnMessageReason} -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end,

    receive
        <<"myOnTerminateReason">> -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive 'DOWN' message")
    end.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    ok.

teardown(_) ->
    ok.
