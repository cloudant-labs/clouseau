-module(monitor_tests).
-include_lib("zeunit/include/zeunit.hrl").

monitor_test_() ->
    {
        "Test support for remote monitors",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            [
                fun t_monitor_pid_noproc/0,
                fun t_monitor_name_noproc/0,
                fun t_monitor_pid/0,
                fun t_monitor_name/0,
                fun t_demonitor_pid/0,
                fun t_demonitor_name/0
            ]
        }
    }.

t_monitor_pid_noproc() ->
    InvalidPid = test_util:spawn_echo(echo_invalid),
    exit(InvalidPid, normal),
    {echo_invalid, ?NodeZ} ! {echo, self(), erlang:system_time(microsecond), 1},
    {error, timeout} = util:receive_msg(util:seconds(1)),
    Ref = monitor(process, InvalidPid),
    ?assertMatch({'DOWN', Ref, process, InvalidPid, noproc}, util:receive_msg()).

t_monitor_name_noproc() ->
    Name = {non_existent, ?NodeZ},
    monitor(process, Name),
    ?assertMatch({'DOWN', _Ref, process, Name, noproc}, util:receive_msg()).

t_monitor_pid() ->
    Pid = test_util:spawn_echo(echo_monitor_pid),
    Ref = monitor(process, Pid),
    exit(Pid, reason),
    ?assertMatch({'DOWN', Ref, process, Pid, reason}, util:receive_msg()).

t_monitor_name() ->
    Pid = test_util:spawn_echo(echo_monitor_name),
    Ref = monitor(process, {echo_monitor_name, ?NodeZ}),
    exit(Pid, reason),
    ?assertMatch({'DOWN', Ref, process, Pid, reason}, util:receive_msg()).

t_demonitor_pid() ->
    Pid = test_util:spawn_echo(echo_demonitor_pid),
    Ref = monitor(process, Pid),
    demonitor(Ref),
    exit(Pid, reason),
    ?assertEqual({error, timeout}, util:receive_msg()).

t_demonitor_name() ->
    Pid = test_util:spawn_echo(echo_demonitor_name),
    Ref = monitor(process, {echo_demonitor_name, ?NodeZ}),
    demonitor(Ref),
    exit(Pid, reason),
    ?assertEqual({error, timeout}, util:receive_msg()).

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    ok.

teardown(_) ->
    ok.
