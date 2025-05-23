-module(index_manager_tests).
-include("zeunit.hrl").

-define(SERVICE, main).

index_manager_service_test_() ->
    {
        "Test IndexManagerService",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            [
                fun t_metrics_format/0
            ]
        }
    }.

%%
%% Verify we get following shape
%% ```erlang
%% {ok, #{
%%   'lru.evictions' => 1,
%%   'lru.misses' => 2,
%%   opens => #{
%%     durationUnit => <<"milliseconds">>,
%%     fifteenMinutesRate => 0.0,
%%     fiveMinutesRate => 0.0,
%%     max => 60.63325,
%%     mean => 31.6208335,
%%     median => 60.63325,
%%     min => 2.6084169999999998,
%%     oneMinuteRate => 0.0,
%%     p75 => 60.63325,
%%     p95 => 60.63325,
%%     p98 => 60.63325,
%%     p99 => 60.63325,
%%     p999 => 60.63325,
%%     rateUnit => <<"events/second">>,
%%     stddev => 29.012416499999997
%%   }
%% }}
%% ```
%%
t_metrics_format() ->
    Result = gen_server:call({?SERVICE, ?NodeZ}, metrics),
    ?assertMatch({ok, #{}}, Result),
    {ok, Metrics} = Result,
    ?assertNonNegInteger(maps:get('lru.evictions', Metrics, {no_such_key, 'lru.evictions'})),
    ?assertNonNegInteger(maps:get('lru.misses', Metrics, {no_such_key, 'lru.misses'})),
    ?assertMatch(#{}, maps:get('opens', Metrics), "Expected 'opens' Timer value to be a map"),
    Timer = maps:get('opens', Metrics, {no_such_key, 'opens'}),
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

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    ok.

teardown(_) ->
    ok.
