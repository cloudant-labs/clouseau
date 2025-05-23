-module(index_tests).
-include("zeunit.hrl").

-define(Analyzer, <<"standard">>).
-define(Signature, <<"d46ff3d065da8e84d4e7fec9d63b99f9">>).

index_service_test_() ->
    {
        "Test IndexService",
        {setup, fun setup/0, fun teardown/1,
            with([
                ?TDEF(t_metrics_format)
            ])}
    }.

%%
%% Verify we get following shape
%% ```erlang
%% {ok, #{
%%   commits => #{
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
%%   },
%%   deletes => #{
%%     durationUnit => <<"milliseconds">>,
%%     fifteenMinutesRate => 0.0,
%%     fiveMinutesRate => 0.0,
%%     max => 0.6695829999999999,
%%     mean => 0.6695829999999999,
%%     median => 0.6695829999999999,
%%     min => 0.6695829999999999,
%%     oneMinuteRate => 0.0,
%%     p75 => 0.6695829999999999,
%%     p95 => 0.6695829999999999,
%%     p98 => 0.6695829999999999,
%%     p99 => 0.6695829999999999,
%%     p999 => 0.6695829999999999,
%%     rateUnit => <<"events/second">>,
%%     stddev => 0.0
%%   },
%%   'partition_search.timeout.count' => 0,
%%   searches => #{
%%     durationUnit => <<"milliseconds">>,
%%     fifteenMinutesRate => 0.0,
%%     fiveMinutesRate => 0.0,
%%     max => 0.059417,
%%     mean => 0.03975,
%%     median => 0.059417,
%%     min => 0.020083,
%%     oneMinuteRate => 0.0,
%%     p75 => 0.059417,
%%     p95 => 0.059417,
%%     p98 => 0.059417,
%%     p99 => 0.059417,
%%     p999 => 0.059417,
%%     rateUnit => <<"events/second">>,
%%     stddev => 0.019667
%%   },
%%   updates => #{
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
%%   }}
%% }}
%% ```
%%
t_metrics_format(IndexPid) ->
    Result = gen_server:call(IndexPid, metrics),
    ?assertMatch({ok, #{}}, Result),
    {ok, Metrics} = Result,
    ?assertNonNegInteger(
        maps:get(
            'partition_search.timeout.count',
            Metrics,
            {no_such_key, 'partition_search.timeout.count'}
        )
    ),

    ?assertMatch(#{}, maps:get('commits', Metrics), "Expected 'commits' Timer value to be a map"),
    CommitsTimer = maps:get('commits', Metrics, {no_such_key, 'commits'}),
    ?assertNonNegFloat(maps:get(p75, CommitsTimer, {no_such_key, p75})),
    ?assertNonNegFloat(maps:get(p95, CommitsTimer, {no_such_key, p95})),
    ?assertNonNegFloat(maps:get(p98, CommitsTimer, {no_such_key, p98})),
    ?assertNonNegFloat(maps:get(p99, CommitsTimer, {no_such_key, p99})),
    ?assertNonNegFloat(maps:get(p999, CommitsTimer, {no_such_key, p999})),
    ?assertNonNegNumber(maps:get(max, CommitsTimer, {no_such_key, max})),
    ?assertNonNegFloat(maps:get(mean, CommitsTimer, {no_such_key, mean})),
    ?assertNonNegFloat(maps:get(median, CommitsTimer, {no_such_key, median})),
    ?assertNonNegNumber(maps:get(min, CommitsTimer, {no_such_key, min})),
    ?assertNonNegFloat(maps:get(stddev, CommitsTimer, {no_such_key, stddev})),
    ?assertNonNegFloat(maps:get(oneMinuteRate, CommitsTimer, {no_such_key, oneMinuteRate})),
    ?assertNonNegFloat(maps:get(fiveMinutesRate, CommitsTimer, {no_such_key, fiveMinutesRate})),
    ?assertNonNegFloat(
        maps:get(fifteenMinutesRate, CommitsTimer, {no_such_key, fifteenMinutesRate})
    ),
    ?assertEqual(
        <<"milliseconds">>, maps:get(durationUnit, CommitsTimer, {no_such_key, durationUnit})
    ),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, CommitsTimer, {no_such_key, rateUnit})),

    ?assertMatch(#{}, maps:get('deletes', Metrics), "Expected 'deletes' Timer value to be a map"),
    DeletesTimer = maps:get('deletes', Metrics, {no_such_key, 'deletes'}),
    ?assertNonNegFloat(maps:get(p75, DeletesTimer, {no_such_key, p75})),
    ?assertNonNegFloat(maps:get(p95, DeletesTimer, {no_such_key, p95})),
    ?assertNonNegFloat(maps:get(p98, DeletesTimer, {no_such_key, p98})),
    ?assertNonNegFloat(maps:get(p99, DeletesTimer, {no_such_key, p99})),
    ?assertNonNegFloat(maps:get(p999, DeletesTimer, {no_such_key, p999})),
    ?assertNonNegNumber(maps:get(max, DeletesTimer, {no_such_key, max})),
    ?assertNonNegFloat(maps:get(mean, DeletesTimer, {no_such_key, mean})),
    ?assertNonNegFloat(maps:get(median, DeletesTimer, {no_such_key, median})),
    ?assertNonNegNumber(maps:get(min, DeletesTimer, {no_such_key, min})),
    ?assertNonNegFloat(maps:get(stddev, DeletesTimer, {no_such_key, stddev})),
    ?assertNonNegFloat(maps:get(oneMinuteRate, DeletesTimer, {no_such_key, oneMinuteRate})),
    ?assertNonNegFloat(maps:get(fiveMinutesRate, DeletesTimer, {no_such_key, fiveMinutesRate})),
    ?assertNonNegFloat(
        maps:get(fifteenMinutesRate, DeletesTimer, {no_such_key, fifteenMinutesRate})
    ),
    ?assertEqual(
        <<"milliseconds">>, maps:get(durationUnit, DeletesTimer, {no_such_key, durationUnit})
    ),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, DeletesTimer, {no_such_key, rateUnit})),

    ?assertMatch(#{}, maps:get('searches', Metrics), "Expected 'searches' Timer value to be a map"),
    SearchesTimer = maps:get('searches', Metrics, {no_such_key, 'searches'}),
    ?assertNonNegFloat(maps:get(p75, SearchesTimer, {no_such_key, p75})),
    ?assertNonNegFloat(maps:get(p95, SearchesTimer, {no_such_key, p95})),
    ?assertNonNegFloat(maps:get(p98, SearchesTimer, {no_such_key, p98})),
    ?assertNonNegFloat(maps:get(p99, SearchesTimer, {no_such_key, p99})),
    ?assertNonNegFloat(maps:get(p999, SearchesTimer, {no_such_key, p999})),
    ?assertNonNegNumber(maps:get(max, SearchesTimer, {no_such_key, max})),
    ?assertNonNegFloat(maps:get(mean, SearchesTimer, {no_such_key, mean})),
    ?assertNonNegFloat(maps:get(median, SearchesTimer, {no_such_key, median})),
    ?assertNonNegNumber(maps:get(min, SearchesTimer, {no_such_key, min})),
    ?assertNonNegFloat(maps:get(stddev, SearchesTimer, {no_such_key, stddev})),
    ?assertNonNegFloat(maps:get(oneMinuteRate, SearchesTimer, {no_such_key, oneMinuteRate})),
    ?assertNonNegFloat(maps:get(fiveMinutesRate, SearchesTimer, {no_such_key, fiveMinutesRate})),
    ?assertNonNegFloat(
        maps:get(fifteenMinutesRate, SearchesTimer, {no_such_key, fifteenMinutesRate})
    ),
    ?assertEqual(
        <<"milliseconds">>, maps:get(durationUnit, SearchesTimer, {no_such_key, durationUnit})
    ),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, SearchesTimer, {no_such_key, rateUnit})),

    ?assertMatch(#{}, maps:get('updates', Metrics), "Expected 'updates' Timer value to be a map"),
    UpdatesTimer = maps:get('updates', Metrics, {no_such_key, 'updates'}),
    ?assertNonNegFloat(maps:get(p75, UpdatesTimer, {no_such_key, p75})),
    ?assertNonNegFloat(maps:get(p95, UpdatesTimer, {no_such_key, p95})),
    ?assertNonNegFloat(maps:get(p98, UpdatesTimer, {no_such_key, p98})),
    ?assertNonNegFloat(maps:get(p99, UpdatesTimer, {no_such_key, p99})),
    ?assertNonNegFloat(maps:get(p999, UpdatesTimer, {no_such_key, p999})),
    ?assertNonNegNumber(maps:get(max, UpdatesTimer, {no_such_key, max})),
    ?assertNonNegFloat(maps:get(mean, UpdatesTimer, {no_such_key, mean})),
    ?assertNonNegFloat(maps:get(median, UpdatesTimer, {no_such_key, median})),
    ?assertNonNegNumber(maps:get(min, UpdatesTimer, {no_such_key, min})),
    ?assertNonNegFloat(maps:get(stddev, UpdatesTimer, {no_such_key, stddev})),
    ?assertNonNegFloat(maps:get(oneMinuteRate, UpdatesTimer, {no_such_key, oneMinuteRate})),
    ?assertNonNegFloat(maps:get(fiveMinutesRate, UpdatesTimer, {no_such_key, fiveMinutesRate})),
    ?assertNonNegFloat(
        maps:get(fifteenMinutesRate, UpdatesTimer, {no_such_key, fifteenMinutesRate})
    ),
    ?assertEqual(
        <<"milliseconds">>, maps:get(durationUnit, UpdatesTimer, {no_such_key, durationUnit})
    ),
    ?assertEqual(<<"events/second">>, maps:get(rateUnit, UpdatesTimer, {no_such_key, rateUnit})),
    ok.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setupDb() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    DbName = test_util:tempdb(),
    ShardsDbName = <<"shards/00000000-ffffffff/", DbName/binary, ".1730151232">>,
    Path = <<ShardsDbName/binary, "/", ?Signature/binary>>,
    {DbName, ShardsDbName, Path}.

setup() ->
    {_, _, Path} = setupDb(),
    {ok, IndexPid} = clouseau_rpc:open_index(self(), Path, ?Analyzer),
    ?assert(is_pid(IndexPid)),
    IndexPid.

teardown(_) ->
    ok.
