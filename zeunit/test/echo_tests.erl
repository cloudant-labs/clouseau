-module(echo_tests).
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
                    ?TDEF_FEX(t_echo),
                    ?TDEF_FEX(t_echo_failure),
                    ?TDEF_FEX(t_echo_spawn),
                    ?TDEF_FEX(t_call_echo),
                    ?TDEF_FEX(t_call_version),
                    ?TDEF_FEX(t_call_build_info)
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

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(),  "Init service is not ready"),
    ok.

teardown(_) ->
    ok.

start_service(Name) ->
    {ok, Pid} = gen_server:call({?INIT_SERVICE, ?NodeZ}, {spawn, echo, Name}),
    ?assert(is_pid(Pid)),
    Pid.

stop_service(_, Pid) ->
    exit(Pid, normal).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%

ensure_semantic(BinaryVersion) when is_binary(BinaryVersion) ->
    [?assert(is_binary_integer(E)) || E <- binary:split(BinaryVersion, <<".">>, [global])].

parse_semantic(BinaryVersion) ->
    ensure_semantic(BinaryVersion),
    list_to_tuple([binary_to_integer(E) || E <- binary:split(BinaryVersion, <<".">>, [global])]).

is_binary_integer(Binary) ->
    try binary_to_integer(Binary) of
        _ -> true
    catch
        throw:badarg -> false
    end.
