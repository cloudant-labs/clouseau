-module(clouseau_ctrl).

-feature(maybe_expr, enable).

%% API exports
-export([
    main/1,
    service_list/1,
    service_restart/1,
    service_info/1,
    service_check/1,
    service_report/1,
    service_metrics/1
]).

-include_lib("clouseau.hrl").

%%====================================================================
%% API functions
%%====================================================================

main(CLIArgs) ->
    clouseau_cli:run(CLIArgs, clouseau_service:known_services()).

service_list(#{} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, Services} ?= clouseau_service:list(Ctx),
        ok ?= non_empty(Services, {error, empty_result}),
        Rows = [
            {Id, [{pid, Value}]}
         || {Id, Value} <- maps:to_list(Services)
        ],
        clouseau_io:print_table(Rows, [
            {50, centre, id},
            {20, centre, pid}
        ])
    else
        {error, Error} ->
            fail("Cannot get list of services: ~p", [Error])
    end.

service_restart(#{id := Id} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, Pid} ?= clouseau_service:restart_service(Id, Ctx),
        io:format("~p~n", [Pid])
    else
        {error, Error} ->
            fail("Cannot get restart service '~s': ~p", [Id, Error])
    end.

service_info(#{id := Id} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, Info} ?= clouseau_service:info(Id, Ctx),
        ok ?= non_empty(Info, {error, empty_result}),
        format_report(Info, Args, [
            {50, centre, info},
            {20, centre, value}
        ])
    else
        {error, Error} ->
            fail("Cannot get info for service '~s': ~p", [Id, Error])
    end.

service_check(#{id := Id} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, #{checks := Checks}} ?= clouseau_service:check_service(Id, Ctx),
        ok ?= non_empty(Checks, {error, empty_result}),
        Rows = [
            {Check, [{result, Result}, {value, Value}]}
         || {Check, {Result, Value}} <- maps:to_list(Checks)
        ],
        clouseau_io:print_table(Rows, [
            {50, right, check},
            {20, left, value},
            {9, centre, result}
        ])
    else
        {error, Error} ->
            fail("Cannot execute check for service '~s': ~p", [Id, Error])
    end.

service_report(#{id := Id} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, #{
            checks := Checks,
            metrics := Metrics,
            info := Info,
            meters := Meters
        }} ?= clouseau_service:check_service(Id, Ctx),
        ok ?= non_empty(Info, {error, empty_result}),
        format_report(Info, Args, [
            {50, centre, info},
            {20, centre, value}
        ]),
        ok ?= non_empty(Checks, {error, empty_result}),
        ChecksRows = [
            {Check, [{result, Result}, {value, Value}]}
         || {Check, {Result, Value}} <- maps:to_list(Checks)
        ],
        clouseau_io:print_table(ChecksRows, [
            {50, right, check},
            {20, left, value},
            {9, centre, result}
        ]),
        ok ?= non_empty(Metrics, {error, empty_result}),
        MetricsRows = [
            {Metric, [{unit, Unit}, {value, Value}]}
         || {Metric, {Unit, Value}} <- maps:to_list(Metrics)
        ],
        clouseau_io:print_table(MetricsRows, [
            {50, centre, metric},
            {20, centre, value},
            {14, left, unit}
        ]),
        ok ?= non_empty(Meters, {error, empty_result}),
        MetersRows = [{Meter, [{value, Value}]} || {Meter, Value} <- maps:to_list(Meters)],
        clouseau_io:print_table(MetersRows, [
            {50, centre, meter},
            {20, centre, value}
        ])
    else
        {error, Error} ->
            fail("Cannot execute check for service '~s': ~p", [Id, Error])
    end.

service_metrics(#{id := Id} = Args) ->
    Ctx = init(Args),
    maybe
        {ok, Metrics} ?= clouseau_service:get_metrics(Id, Ctx),
        Rows = [
            {Metric, [{unit, Unit}, {value, Value}]}
         || {Metric, {Unit, Value}} <- maps:to_list(Metrics)
        ],
        clouseau_io:print_table(Rows, [
            {50, centre, metric},
            {20, centre, value},
            {20, left, unit}
        ])
    else
        {error, Error} ->
            fail("Cannot execute check for service '~s': ~p", [Id, Error])
    end.

%%====================================================================
%% Internal functions
%%====================================================================

init(#{} = Args) ->
    Ctx = create_ctx(Args),
    clouseau_utils:init(Ctx),
    Ctx.

create_ctx(#{
    name := Name,
    cookie := Cookie,
    host := Host,
    script_path := ScriptPath,
    script_name := ScriptName,
    format := Format
}) ->
    % elp:ignore atoms_exhaustion - Atom exhastion is not an issue for CLIs (invoked once)
    RemoteNode = list_to_atom(Name ++ "@" ++ Host),
    % elp:ignore atoms_exhaustion - Atom exhastion is not an issue for CLIs (invoked once)
    LocalNode = list_to_atom(ScriptName ++ "_" ++ clouseau_utils:random_string(6) ++ "@127.0.0.1"),

    #clouseau_ctx{
        name = Name,
        cookie = Cookie,
        host = Host,
        script_path = ScriptPath,
        script_name = ScriptName,
        local = LocalNode,
        remote = RemoteNode,
        format = Format
    }.

format_report(Results, #{format := table}, TableSpec) ->
    clouseau_io:print_report(maps:to_list(Results), TableSpec).

fail(Format, Args) ->
    clouseau_io:format_nok(Format, Args),
    clouseau_cli:help(clouseau_service:known_services()),
    halt(1).

non_empty(#{} = Map, _) when map_size(Map) > 0 ->
    ok;
non_empty(_, Else) ->
    Else.
