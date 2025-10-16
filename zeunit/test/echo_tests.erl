-module(echo_tests).
-include("zeunit.hrl").

-define(INIT_SERVICE, init).
-define(TIMEOUT_IN_MS, 1000).

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
                    ?TDEF_FEX(t_echo),
                    ?TDEF_FEX(t_echo_failure),
                    ?TDEF_FEX(t_echo_spawn),
                    ?TDEF_FEX(t_call_echo),
                    ?TDEF_FEX(t_call_version),
                    ?TDEF_FEX(t_call_build_info),
                    ?TDEF_FEX(t_concurrent_call_echo),
                    ?TDEF_FEX(t_ioq_call_echo),
                    ?TDEF_FEX(t_concurrent_ioq_call_echo),
                    ?TDEF_FEX(t_scheduler_is_not_blocking)
                ]
            }
        }
    }.

t_echo(Name, _) ->
    {Name, ?NodeZ} ! {echo, self(), erlang:system_time(microsecond), 1},
    {Symbol, Pid, _, _, _, Seq} = util:receive_msg(10000),
    ?assertEqual({Symbol, Pid, Seq}, {echo_reply, self(), 1}).

t_echo_failure(Name, _) ->
    {Name, ?NodeZ} ! {echo, self(), unexpected_msg},
    ?assertEqual({error, timeout}, util:receive_msg(100)).

t_echo_spawn(_Name, _) ->
    {?INIT_SERVICE, ?NodeZ} ! {spawn, self(), echo, echoX},
    ({_, _, Pid} = Message) = util:receive_msg(),
    ?assert(is_pid(Pid)),
    exit(Pid, normal),
    ?assertMatch({spawned, echoX, _}, Message).

t_concurrent_call_echo(Name, _) ->
    Self = self(),
    Concurrency = 100,
    lists:foreach(
        fun(Idx) ->
            spawn(fun() ->
                case gen_server:call({Name, ?NodeZ}, {echo, Idx}) of
                    {echo, Idx} ->
                        Self ! Idx;
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
                Idx when is_integer(Idx) ->
                    Idx
            after ?TIMEOUT_IN_MS ->
                timeout
            end
        end,
        lists:seq(1, Concurrency)
    ),
    ?assertEqual(Concurrency, length([Idx || Idx <- Results, is_integer(Idx)])),
    ok.

t_call_echo(Name, _) ->
    ?assertEqual({echo, {}}, gen_server:call({Name, ?NodeZ}, {echo, {}})).

t_call_version(_, _) ->
    Version = gen_server:call({?INIT_SERVICE, ?NodeZ}, version),
    ensure_semantic(Version),
    ?assertMatch({3, _, _}, parse_semantic(Version)).

t_call_build_info(_, _) ->
    Info = gen_server:call({?INIT_SERVICE, ?NodeZ}, build_info),
    #{
        clouseau := Clouseau,
        sbt := Sbt,
        scala := Scala
    } = Info,

    ensure_semantic(Clouseau),
    ensure_semantic(Sbt),
    ensure_semantic(Scala).

t_ioq_call_echo(Name, _) ->
    ?assertEqual({echo, {}}, ioq_call({Name, ?NodeZ}, {echo, {}})).

t_concurrent_ioq_call_echo(Name, _) ->
    Self = self(),
    Concurrency = 100,
    lists:foreach(
        fun(Idx) ->
            spawn(fun() ->
                case ioq_call({Name, ?NodeZ}, {echo, Idx}) of
                    {echo, Idx} ->
                        Self ! Idx;
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
                Idx when is_integer(Idx) ->
                    Idx
            after ?TIMEOUT_IN_MS ->
                timeout
            end
        end,
        lists:seq(1, Concurrency)
    ),
    ?assertEqual(Concurrency, length([Idx || Idx <- Results, is_integer(Idx)])),
    ok.

%% make sure other actors are still reposnding when one of the actors is blocked
t_scheduler_is_not_blocking(Name, _) ->
    Pid = start_service(name_with_suffix(Name, another)),
    %% Block the actor in handleInfo so it cannot handle other messages
    {Name, ?NodeZ} ! {block_for_ms, 5000},
    ?assertEqual({echo, {}}, gen_server:call(Pid, {echo, {}})),
    ?assertEqual(ok, util:stop_service(Pid), "Expected to be able to kill the service"),
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
    util:stop_service(Pid).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%

name_with_suffix(Name, Suffix) when is_atom(Name) andalso is_atom(Suffix) ->
    list_to_atom(atom_to_list(Name) ++ "_" ++ atom_to_list(Suffix)).

ensure_semantic(BinaryVersion) when is_binary(BinaryVersion) ->
    Version = hd(binary:split(BinaryVersion, <<"-">>)),
    [?assert(is_binary_integer(E)) || E <- binary:split(Version, <<".">>, [global])].

parse_semantic(BinaryVersion) ->
    ensure_semantic(BinaryVersion),
    Version = hd(binary:split(BinaryVersion, <<"-">>)),
    list_to_tuple([binary_to_integer(E) || E <- binary:split(Version, <<".">>, [global])]).

is_binary_integer(Binary) ->
    try binary_to_integer(Binary) of
        _ -> true
    catch
        throw:badarg -> false
    end.

ioq_call(Remote, Call) ->
    Ref = erlang:monitor(process, Remote),
    % make the request
    Remote ! {'$gen_call', {self(), Ref}, Call},
    receive
        {Ref, Reply} -> Reply
    after ?TIMEOUT_IN_MS ->
        timeout
    end.
