-module(init_tests).
-include("zeunit.hrl").

-define(INIT_SERVICE, init).

echo_service_test_() ->
    {
        "Test Echo Service",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            {
                foreachx,
                fun start_service/1,
                fun stop_service/2,
                [
                    ?TDEF_FEX(t_metrics_format),
                    ?TDEF_FEX(t_ping_event),
                    ?TDEF_FEX(t_ping_call)
                ]
            }
        }
    }.

%%
%% Verify we get following shape
%% ```erlang
%% {ok, #{
%%   'spawned.failure' => 0,
%%   'spawned.success' => 1,
%%   'spawned.timer' => #{
%%     p75 => 64.0,
%%     p95 => 64.0,
%%     p98 => 64.0,
%%     p99 => 64.0,
%%     p999 => 64.0,
%%     max => 64,
%%     mean => 64.0,
%%     median => 64.0,
%%     min => 64,
%%     stddev => 0.0,
%%     oneMinuteRate => 23.2,
%%     fiveMinutesRate => 23.4,
%%     fifteenMinutesRate => 21.1,
%%     durationUnit => <<"microseconds">>,
%%     rateUnit => <<"events/second">>
%%   }
%% }}
%% ```
%%
t_metrics_format(_Name, _) ->
    Result = gen_server:call({?INIT_SERVICE, ?NodeZ}, metrics),
    ?assertMatch({ok, #{}}, Result),
    {ok, Metrics} = Result,
    ?assertNonNegInteger(maps:get('spawned.failure', Metrics, {no_such_key, 'spawned.failure'})),
    ?assertNonNegInteger(maps:get('spawned.success', Metrics, {no_such_key, 'spawned.success'})),
    ?assertMatch(#{}, maps:get('spawned.timer', Metrics), "Expected timer value to be a map"),
    Timer = maps:get('spawned.timer', Metrics, {no_such_key, 'spawned.timer'}),
    ?assertNonNegFloat(maps:get(p75, Timer, {no_such_key, p75})),
    ?assertNonNegFloat(maps:get(p95, Timer, {no_such_key, p95})),
    ?assertNonNegFloat(maps:get(p98, Timer, {no_such_key, p98})),
    ?assertNonNegFloat(maps:get(p99, Timer, {no_such_key, p99})),
    ?assertNonNegFloat(maps:get(p999, Timer, {no_such_key, p999})),
    ?assertNonNegNumber(maps:get(max, Timer, {no_such_key, max})),
    ?assertNonNegFloat(maps:get(mean, Timer, {no_such_key, mean})),
    ?assertNonNegFloat(maps:get(median, Timer, {no_such_key, median})),
    ?assertNonNegNumber(maps:get(min, Timer, {no_such_key, min})),
    ?assertNonNegFloat(maps:get(stddev, Timer, {no_such_key, stddev})),
    ?assertNonNegFloat(maps:get(oneMinuteRate, Timer, {no_such_key, oneMinuteRate})),
    ?assertNonNegFloat(maps:get(fiveMinutesRate, Timer, {no_such_key, fiveMinutesRate})),
    ?assertNonNegFloat(maps:get(fifteenMinutesRate, Timer, {no_such_key, fifteenMinutesRate})),
    ?assertEqual(<<"milliseconds">>, maps:get(durationUnit, Timer, {no_such_key, durationUnit})),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, Timer, {no_such_key, rateUnit})),
    ok.

t_ping_event(_Name, _) ->
    Ref = make_ref(),
    {?INIT_SERVICE, ?NodeZ} ! {ping, self(), Ref},
    Reply = util:receive_pong(Ref),
    ?assertEqual(pong, Reply, ?format("Expected 'pong', got ~p", [Reply])),
    ok.

t_ping_call(_Name, _) ->
    Reply = gen_server:call({?INIT_SERVICE, ?NodeZ}, ping),
    ?assertEqual(pong, Reply, ?format("Expected 'pong', got ~p", [Reply])),
    ok.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    ok.

teardown(_) ->
    ok.

start_service(Name) ->
    {ok, Pid} = gen_server:call({?INIT_SERVICE, ?NodeZ}, {spawn, echo, Name}),
    ?assert(is_pid(Pid)),
    Pid.

stop_service(_, Pid) ->
    exit(Pid, normal).
