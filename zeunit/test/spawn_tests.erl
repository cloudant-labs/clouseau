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
    Self = self(),
    T1 = ts(),
    lists:foreach(
        fun(Idx) ->
            Name = process_name(Prefix, Idx),
            spawn(fun() ->
                Pid = start_service(Name),
                T1Call = ts(),
                case gen_server:call(Pid, {echo, Idx}) of
                    {echo, Idx} ->
                        T2Call = ts(),
                        Self ! {Idx, T2Call - T1Call};
                    Else ->
                        ?debugFmt("Received unexpected event for idx=~i ~p~n", [Idx, Else])
                end
            end)
        end,
        lists:seq(1, Concurrency)
    ),
    Results = lists:map(
        fun(_) ->
            receive
                {Idx, Time} when is_integer(Idx) ->
                    {Idx, Time}
            after ?TIMEOUT_IN_MS ->
                timeout
            end
        end,
        lists:seq(1, Concurrency)
    ),
    T2 = ts(),
    %% make sure there were no timeouts
    ?assertEqual(Concurrency, length([Idx || {Idx, _} <- Results, is_integer(Idx)])),
    TotalTime = lists:foldl(fun({_Idx, RoundTripTime}, Acc) -> RoundTripTime + Acc end, 0, Results),
    %% make sure the calls happened concurrently
    ?assert(TotalTime > (T2 - T1) * ?ACCEPTABLE_CONCURENCY_TIME_RATIO),
    ok.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    Prefix = atom_to_binary(test_util:random_atom()),
    Concurrency = 100,
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
