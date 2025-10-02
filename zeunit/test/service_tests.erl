-module(service_tests).

-include("zeunit.hrl").

-define(INIT_SERVICE, init).
-define(REX_SERVICE, rex).
-define(TIMEOUT_IN_MS, 3000).

-define(FAIL(Reason),
    erlang:error(
        {fail, [
            {module, ?MODULE},
            {line, ?LINE},
            {reason, Reason}
        ]}
    )
).

service_test_() ->
    {
        "Test Rex Service",
        {
            setup,
            fun setup/0,
            fun teardown/1,
            {
                foreachx,
                fun start_services/1,
                fun stop_services/2,
                [
                    ?TDEF_FEX(t_internal_call_to_pid),
                    ?TDEF_FEX(t_internal_call_to_name),
                    ?TDEF_FEX(t_internal_cast_to_pid),
                    ?TDEF_FEX(t_internal_cast_to_name)
                ]
            }
        }
    }.

t_internal_call_to_pid(Id, {ServicePid, _TargetName, TargetPid}) ->
    Response = gen_server:call(ServicePid, {call, TargetPid, {echo, Id}}),
    ?assertMatch(
        {ok, _}, Response, ?format("Expected the gen_server:call to succeed, got ~p", [Response])
    ),
    {ok, Result} = Response,
    ?assertMatch(
        {echo, t_internal_call_to_pid},
        Result,
        ?format("Expected to receive {echo, Id} tuple, got ~p", [Result])
    ),
    ok.

t_internal_call_to_name(Id, {ServicePid, TargetName, _TargetPid}) ->
    Response = gen_server:call(ServicePid, {call, TargetName, {echo, Id}}),
    ?assertMatch(
        {ok, _}, Response, ?format("Expected the gen_server:call to succeed, got ~p", [Response])
    ),
    {ok, Result} = Response,
    ?assertMatch(
        {echo, t_internal_call_to_name},
        Result,
        ?format("Expected to receive {echo, Id} tuple, got ~p", [Result])
    ),
    ok.

t_internal_cast_to_pid(Id, {ServicePid, _TargetName, TargetPid}) ->
    Ref = make_ref(),
    Response = gen_server:call(ServicePid, {cast, TargetPid, {echo, self(), {Ref, Id}}}),
    ?assertMatch(
        ok, Response, ?format("Expected the gen_server:call to succeed, got ~p", [Response])
    ),
    receive
        {echo, {Ref, Id}} -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive a message")
    end.

t_internal_cast_to_name(Id, {ServicePid, TargetName, _TargetPid}) ->
    Ref = make_ref(),
    Response = gen_server:call(ServicePid, {cast, TargetName, {echo, self(), {Ref, Id}}}),
    ?assertMatch(
        ok, Response, ?format("Expected the gen_server:call to succeed, got ~p", [Response])
    ),
    receive
        {echo, {Ref, Id}} -> ok
    after ?TIMEOUT_IN_MS ->
        ?FAIL("Expected to receive a message")
    end.

%%%%%%%%%%%%%%% Setup Functions %%%%%%%%%%%%%%%

setup() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    ok.

teardown(_) ->
    ok.

start_services(Id) ->
    ServicePid = start_service(Id),
    TargetName = name_with_suffix(Id, target),
    TargetPid = start_service(TargetName),
    {ServicePid, TargetName, TargetPid}.

stop_services(Ctx, {ServicePid, _TargetName, TargetPid}) ->
    stop_service(Ctx, ServicePid),
    stop_service(Ctx, TargetPid).

start_service(Name) ->
    {ok, Pid} = gen_server:call({?INIT_SERVICE, ?NodeZ}, {spawn, echo, Name}),
    Pid.

stop_service(_, Pid) ->
    catch exit(Pid, normal).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%

name_with_suffix(Name, Suffix) when is_atom(Name) andalso is_atom(Suffix) ->
    list_to_atom(atom_to_list(Name) ++ "_" ++ atom_to_list(Suffix)).
