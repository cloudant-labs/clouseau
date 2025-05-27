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
%%     count => 12,
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
%%     count => 12,
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
%%     count => 12,
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
%%     count => 12,
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
    metrics_asserts:assertTimerFormat(CommitsTimer),

    ?assertMatch(#{}, maps:get('deletes', Metrics), "Expected 'deletes' Timer value to be a map"),
    DeletesTimer = maps:get('deletes', Metrics, {no_such_key, 'deletes'}),
    metrics_asserts:assertTimerFormat(DeletesTimer),

    ?assertMatch(#{}, maps:get('searches', Metrics), "Expected 'searches' Timer value to be a map"),
    SearchesTimer = maps:get('searches', Metrics, {no_such_key, 'searches'}),
    metrics_asserts:assertTimerFormat(SearchesTimer),

    ?assertMatch(#{}, maps:get('updates', Metrics), "Expected 'updates' Timer value to be a map"),
    UpdatesTimer = maps:get('updates', Metrics, {no_such_key, 'updates'}),
    metrics_asserts:assertTimerFormat(UpdatesTimer),
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
