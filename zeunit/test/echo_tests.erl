-module(echo_tests).
-include("zeunit.hrl").

echo_service_test_() ->
    {
        "Test Echo Service",
        {
            setup,
            fun test_util:setup/0,
            fun test_util:teardown/1,
            [
                fun t_echo/0,
                fun t_echo_failure/0,
                fun t_call_echo/0,
                fun t_call_version/0,
                fun t_call_build_info/0
            ]
        }
    }.

t_echo() ->
    {coordinator, ?NodeZ} ! {echo, self(), erlang:system_time(microsecond), 1},
    {Symbol, Pid, _, _, _, Seq} = util:receive_msg(),
    ?assertEqual({Symbol, Pid, Seq}, {echo_reply, self(), 1}).

t_echo_failure() ->
    {coordinator, ?NodeZ} ! {echo, self(), unexpected_msg},
    ?assertEqual({error, timeout}, util:receive_msg(100)).

t_call_echo() ->
    ?assertEqual({echo, {}}, gen_server:call({coordinator, ?NodeZ}, {echo, {}})).

t_call_version() ->
    Version = gen_server:call({coordinator, ?NodeZ}, version),
    ensure_semantic(Version),
    ?assertMatch({3, _, _}, parse_semantic(Version)).

t_call_build_info() ->
    Info = gen_server:call({coordinator, ?NodeZ}, build_info),
    #{
        clouseau := Clouseau,
        sbt := Sbt,
        scala := Scala
    } = Info,

    ensure_semantic(Clouseau),
    ensure_semantic(Sbt),
    ensure_semantic(Scala).

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
