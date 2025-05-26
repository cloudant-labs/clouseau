-module(metrics_asserts).
-export([
    assertTimerFormat/1
]).

-include("zeunit.hrl").

%%
%% Verify we get following shape
%% ```erlang
%% #{
%%     durationUnit => <<"milliseconds">>,
%%     fifteenMinutesRate => 0.0,
%%     fiveMinutesRate => 0.0,
%%     max => 0.0,
%%     mean => 0.0,
%%     median => 0.0,
%%     min => 0.0,
%%     oneMinuteRate => 0.0,
%%     p75 => 0.0,
%%     p95 => 0.0,
%%     p98 => 0.0,
%%     p99 => 0.0,
%%     p999 => 0.0,
%%     rateUnit => <<"events/second">>,
%%     stddev => 0.0
%% }.
%% ```

-spec assertTimerFormat(any()) -> ok | error(assert_error()).
assertTimerFormat(Timer) ->
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
    ?assertNonNegFloat(
        maps:get(fifteenMinutesRate, Timer, {no_such_key, fifteenMinutesRate})
    ),
    ?assertEqual(
        <<"milliseconds">>, maps:get(durationUnit, Timer, {no_such_key, durationUnit})
    ),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, Timer, {no_such_key, rateUnit})),
    ok.
