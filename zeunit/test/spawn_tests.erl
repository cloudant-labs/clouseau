% To run only this suite use
% ```
% make zeunit suites=spawn_tests
% ```

-module(spawn_tests).
-include("zeunit.hrl").

-define(INIT_SERVICE, init).
-define(TIMEOUT_IN_MS, 1000).
% We expect at least 3x speed up when doing things in parallel
-define(ACCEPTABLE_CONCURENCY_TIME_RATIO, 3).

spawn_test_() ->
    {
        "Test Echo Service",
        {
            foreach,
            fun setup/0,
            fun teardown/1,
            [
                ?TDEF_FE(t_spawn_many)
            ]
        }
    }.

t_spawn_many({Prefix, Concurrency}) ->
    ets:new(t_spawn_many_results, [set, public, named_table]),
    T1 = ts(),
    lists:foreach(
        fun(Idx) ->
            Name = process_name(Prefix, Idx),
            spawn(fun() ->
                Pid = start_service(Name),
                test_util:rand_delay_ms(500),
                TI1 = ts(),
                case gen_server:call(Pid, {echo, Idx}) of
                    {echo, Idx} ->
                        ets:insert(t_spawn_many_results, {Idx, ts() - TI1});
                    Else ->
                        ?debugFmt("Received unexpected event for idx=~i ~p~n", [Idx, Else])
                end
            end)
        end,
        lists:seq(1, Concurrency)
    ),
    NResults = util:wait_value(
        fun() -> ets:info(t_spawn_many_results, size) end, Concurrency, 5000
    ),
    T2 = ts(),
    ?assertEqual(Concurrency, NResults),
    Stats = bear:get_statistics([Duration || {_Idx, Duration} <- ets:tab2list(t_spawn_many_results)]),
    io:format(user, "~nRound trip time for concurrent gen_server:call (in msec)~n", []),
    print_statistics(Stats),
    EstimatedSequentialTime = estimate_seq_time(Stats),
    io:format(user, "~nEstimated sequential time: ~p msec~n", [EstimatedSequentialTime]),
    ParallelTime = T2 - T1,
    io:format(user, "~nParallel time: ~p msec~n~n", [ParallelTime]),
    ?assert(
        ParallelTime < EstimatedSequentialTime,
        ?format("Expected ParallelTime(=~p) < EstimatedSequentialTime(=~p)", [
            ParallelTime, EstimatedSequentialTime
        ])
    ),
    ok.

print_statistics(Stats) ->
    [
        io:format(user, "|~20.19s|~12.11w|~n", [Key, Value])
     || {Key, Value} <- Stats, Key /= percentile, Key /= histogram
    ],
    io:format(user, "~npercentile~n", []),
    [
        io:format(user, "| ~-10w|~12.11w|~n", [Key, Value])
     || {Key, Value} <- proplists:get_value(percentile, Stats)
    ],
    io:format(user, "~nhistogram~n", []),
    [
        io:format(user, "| ~-10w|~12.11w|~n", [Key, Value])
     || {Key, Value} <- proplists:get_value(histogram, Stats)
    ],
    ok.

estimate_seq_time(Stats) ->
    Percentiles = proplists:get_value(percentile, Stats),
    P95 = proplists:get_value(95, Percentiles),
    N = proplists:get_value(n, Stats),
    N * P95.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    Prefix = atom_to_binary(test_util:random_atom()),
    Concurrency = 300,
    {Prefix, Concurrency}.

teardown({Prefix, Concurrency}) ->
    lists:foreach(
        fun(Idx) ->
            stop_service(process_name(Prefix, Idx))
        end,
        lists:seq(1, Concurrency)
    ),
    ok.

start_service(Name) ->
    {ok, Pid} = gen_server:call({?INIT_SERVICE, ?NodeZ}, {spawn, echo, Name}),
    ?assert(is_pid(Pid)),
    Pid.

stop_service(Name) ->
    catch exit({Name, ?NodeZ}, normal).

%%%%%%%%%%%%%%% Helper Functions %%%%%%%%%%%%%%%

process_name(Prefix, Idx) ->
    IdxBin = integer_to_binary(Idx),
    binary_to_atom(<<Prefix/binary, IdxBin/binary>>).

ts() ->
    erlang:system_time(millisecond).
