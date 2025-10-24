% To run only this suite use
% ```
% make zeunit suites=rex_tests
% ```

-module(rex_tests).

-include("zeunit.hrl").

-define(INIT_SERVICE, init).
-define(REX_SERVICE, rex).

rex_service_test_() ->
    {
        "Test Rex Service",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            {
                foreachx,
                fun start_service/1,
                fun stop_service/2,
                [
                    ?TDEF_FEX(t_gen_server_top),
                    ?TDEF_FEX(t_gen_server_top_meter),
                    ?TDEF_FEX(t_erl_call_top),
                    ?TDEF_FEX(t_erl_call_top_meter)
                ]
            }
        }
    }.

t_gen_server_top(_Name, _) ->
    {ok, Infos} = gen_server:call({?REX_SERVICE, ?NodeZ}, {top, message_queue_len}),
    Sorted = sort_by_name(Infos),
    ?assertMatch(
        [
            {process_info, #{message_queue_len := _, name := analyzer, pid := _, tags := _}},
            {process_info, #{message_queue_len := _, name := cleanup, pid := _, tags := _}},
            {process_info, #{message_queue_len := _, name := init, pid := _, tags := _}},
            {process_info, #{message_queue_len := _, name := main, pid := _, tags := _}},
            {process_info, #{message_queue_len := _, name := rex, pid := _, tags := _}},
            {process_info, #{message_queue_len := _, name := sup, pid := _, tags := _}}
            | _
        ],
        Sorted,
        ?format("Expected to get info from each actor, got: ~p", [Sorted])
    ),

    EchoInfo = find_by_name(t_gen_server_top, Infos),
    ?assertMatch(
        {process_info, #{}}, EchoInfo, ?format("Expected process_info, got: ~p", [EchoInfo])
    ),
    ?assertMatch(
        {process_info, #{pid := P}} when is_pid(P),
        EchoInfo,
        ?format("Expected to see valid Pid, got ~p", [EchoInfo])
    ),
    ?assertMatch(
        {process_info, #{message_queue_len := Q}} when Q > 0,
        EchoInfo,
        "Expected to see piled up messages"
    ),
    ?assertMatch(
        {process_info, #{tags := [<<"t_gen_server_top">>]}},
        EchoInfo,
        ?format("Expected to see correct tags, got ~p", [EchoInfo])
    ),

    ok.

t_gen_server_top_meter(Name, _) ->
    {ok, Infos} = gen_server:call({?REX_SERVICE, ?NodeZ}, {topMeters, mailbox, composite}),
    Sorted = sort_by_name(Infos),
    ?assertMatch(
        [
            {meter_info, #{
                meter_name := composite, name := analyzer, pid := _, tags := _, value := _
            }},
            {meter_info, #{
                meter_name := composite, name := cleanup, pid := _, tags := _, value := _
            }},
            {meter_info, #{meter_name := composite, name := init, pid := _, tags := _, value := _}},
            {meter_info, #{meter_name := composite, name := main, pid := _, tags := _, value := _}},
            {meter_info, #{meter_name := composite, name := rex, pid := _, tags := _, value := _}},
            {meter_info, #{meter_name := composite, name := sup, pid := _, tags := _, value := _}}
            | _
        ],
        Sorted,
        ?format("Expected to get info from each actor, got: ~p", [Sorted])
    ),

    EchoInfo = find_by_name(Name, Infos),
    ?assertMatch(
        {meter_info, #{}}, EchoInfo, ?format("Expected meter_info, got: ~p", [EchoInfo])
    ),
    ?assertMatch(
        {meter_info, #{pid := P}} when is_pid(P),
        EchoInfo,
        ?format("Expected to see valid Pid, got ~p", [EchoInfo])
    ),
    ?assertMatch(
        {meter_info, #{value := Q}} when Q > 0.1,
        EchoInfo,
        "Expected to see piled up messages"
    ),
    ?assertMatch(
        {meter_info, #{tags := [<<"mailbox">>, <<"t_gen_server_top_meter">>]}},
        EchoInfo,
        ?format("Expected to see correct tags, got ~p", [EchoInfo])
    ),

    ok.

t_erl_call_top(_Name, _) ->
    {0, Reply} = erl_call(clouseau, top, [message_queue_len]),
    ?assertMatch({ok, _Result}, Reply, ?format("Expected an {ok, Reply} tuple, got: ~p", [Reply])),
    {ok, Infos} = Reply,
    Sorted = sort_by_name(Infos),
    ?assertMatch(
        [
            {process_info, #{
                message_queue_len := _,
                name := analyzer,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _
            }},
            {process_info, #{
                message_queue_len := _,
                name := cleanup,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _
            }},
            {process_info, #{
                message_queue_len := _, name := init, pid := "<clouseau1@127.0.0.1" ++ _, tags := _
            }},
            {process_info, #{
                message_queue_len := _, name := main, pid := "<clouseau1@127.0.0.1" ++ _, tags := _
            }},
            {process_info, #{
                message_queue_len := _, name := rex, pid := "<clouseau1@127.0.0.1" ++ _, tags := _
            }},
            {process_info, #{
                message_queue_len := _, name := sup, pid := "<clouseau1@127.0.0.1" ++ _, tags := _
            }}
            | _
        ],
        Sorted,
        ?format("Expected to get info from each actor, got: ~p", [Sorted])
    ),

    EchoInfo = find_by_name(t_erl_call_top, Infos),
    ?assertMatch(
        {process_info, #{}}, EchoInfo, ?format("Expected process_info, got: ~p", [EchoInfo])
    ),
    ?assertMatch(
        {process_info, #{pid := "<clouseau1@127.0.0.1" ++ _}},
        EchoInfo,
        ?format("Expected to see valid Pid, got ~p", [EchoInfo])
    ),
    ?assertMatch(
        {process_info, #{message_queue_len := Q}} when Q > 0,
        EchoInfo,
        "Expected to see piled up messages"
    ),
    ?assertMatch(
        {process_info, #{tags := ["t_erl_call_top"]}},
        EchoInfo,
        ?format("Expected to see correct tags, got ~p", [EchoInfo])
    ),
    ok.

t_erl_call_top_meter(Name, _) ->
    {0, Reply} = erl_call(clouseau, topMeters, [{mailbox, composite}]),
    ?assertMatch({ok, _Result}, Reply, ?format("Expected an {ok, Reply} tuple, got: ~p", [Reply])),
    {ok, Infos} = Reply,
    Sorted = sort_by_name(Infos),
    ?assertMatch(
        [
            {meter_info, #{
                meter_name := composite,
                name := analyzer,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }},
            {meter_info, #{
                meter_name := composite,
                name := cleanup,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }},
            {meter_info, #{
                meter_name := composite,
                name := init,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }},
            {meter_info, #{
                meter_name := composite,
                name := main,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }},
            {meter_info, #{
                meter_name := composite,
                name := rex,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }},
            {meter_info, #{
                meter_name := composite,
                name := sup,
                pid := "<clouseau1@127.0.0.1" ++ _,
                tags := _,
                value := _
            }}
            | _
        ],
        Sorted,
        ?format("Expected to get info from each actor, got: ~p", [Sorted])
    ),

    EchoInfo = find_by_name(Name, Infos),
    ?assertMatch(
        {meter_info, #{}}, EchoInfo, ?format("Expected meter_info, got: ~p", [EchoInfo])
    ),
    ?assertMatch(
        {meter_info, #{pid := "<clouseau1@127.0.0.1" ++ _}},
        EchoInfo,
        ?format("Expected to see valid Pid, got ~p", [EchoInfo])
    ),
    ?assertMatch(
        {meter_info, #{value := Q}} when Q > 0.1,
        EchoInfo,
        "Expected to see piled up messages"
    ),
    ?assertMatch(
        {meter_info, #{tags := ["mailbox", "t_erl_call_top_meter"]}},
        EchoInfo,
        ?format("Expected to see correct tags, got ~p", [EchoInfo])
    ),
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
    %% Block the actor in handleInfo so it cannot handle other messages
    Pid ! {block_for_ms, 5000},
    %% Send many messages to actor so they pile up in the mailbox
    spawn(fun() ->
        [Pid ! {echo, self(), erlang:system_time(microsecond), Seq} || Seq <- lists:seq(1, 1000)]
    end),
    Pid.

stop_service(_, Pid) ->
    util:stop_service(Pid).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%

% the sort by name function uses lexical orderting except it puts the processes with names
% equal to `none` at the end of the list
sort_by_name(Infos) ->
    {Named, Rest} = lists:partition(fun({_, #{name := A}}) -> A /= none end, Infos),
    Sorted = lists:sort(fun({_, #{name := A}}, {_, #{name := B}}) -> A < B end, Named),
    Sorted ++ Rest.

find_by_name(Name, Infos) ->
    {value, Info} = lists:search(fun({_, #{name := A}}) -> A == Name end, Infos),
    Info.

erl_call(M, F, A) ->
    Cmd = io_lib:format("erl_call -n 'clouseau1@127.0.0.1' -a '~s ~s ~p'", [M, F, A]),
    Opts = [exit_status],
    Port = open_port({spawn, Cmd}, Opts),
    {Rc, Body} = do_read(Port, []),
    try
        {ok, Tokens, _} = erl_scan:string(Body ++ "."),
        {ok, Term} = erl_parse:parse_term(Tokens),
        {Rc, Term}
    catch
        _:Error ->
            {error, Error, Body}
    end.

do_read(Port, Acc) ->
    receive
        {Port, {data, StdOut}} ->
            do_read(Port, Acc ++ StdOut);
        {Port, {exit_status, Rc}} ->
            {Rc, Acc}
    end.
