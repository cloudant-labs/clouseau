-module(clouseau_service).

-feature(maybe_expr, enable).

-export([
    known_services/0,
    list/1,
    wait_main_service/1,
    wait_main_service/2,
    check_service/2,
    info/2,
    get_metrics/2,
    get_meters/2,
    check_ping/2,
    stop_service/2,
    restart_service/2
]).

-include_lib("clouseau.hrl").

-define(PING_TIMEOUT_IN_MS, 10_000).
-define(TIMEOUT_IN_MS, 3000).
-define(SERVICE_CHECK_ATTEMPTS, 30).
-define(SERVICE_CHECK_WAITTIME_IN_MS, 1500).

-define(is_known_id(Id), Id == init; Id == rex; Id == analyzer; Id == cleanup; Id == main).

-type known_services() :: init | rex | analyzer | cleanup | main.

-type metrics_result() :: #{string() => {string(), number()}}.
-type check_result() :: #{string() => {success | failure, string()}}.
-type info_result() :: #{string() => any()}.
-type meters_result() :: #{string => number}.

-type supported_metric() :: #{
    fifteenMinutesRate => number(), count => number(), rateUnit := binary()
}.

known_services() ->
    [init, rex, analyzer, cleanup, main].

list(#clouseau_ctx{} = Ctx) ->
    rex_rpc({service, list}, Ctx).

wait_main_service(#clouseau_ctx{} = Ctx) ->
    is_binary(wait_main_service(Ctx, ?SERVICE_CHECK_ATTEMPTS)).

wait_main_service(Ctx, RetriesN) ->
    clouseau_utils:concurrent_retry(RetriesN, fun() ->
        try main_rpc(version, Ctx, ?SERVICE_CHECK_WAITTIME_IN_MS) of
            timeout ->
                wait;
            {ok, Version} ->
                Version
        catch
            _:_ ->
                wait
        end
    end).

-spec check_service(Id :: known_services(), Ctx :: #clouseau_ctx{}) ->
    {ok, #{
        checks => check_result(),
        info => info_result(),
        metrics => metrics_result(),
        meters => meters_result()
    }}
    | {error, Reason :: term()}.

check_service(Id, #clouseau_ctx{} = Ctx) when ?is_known_id(Id) ->
    maybe
        {ok, Pid} ?= whereis(Id, Ctx),
        PingResult = check_ping(Id, Ctx) == pong,
        {ok, Info} ?= info(Pid, Ctx),
        {ok, Metrics} ?= metrics(Pid),
        Checks = metrics_to_check(Id, Metrics),
        {ok, Meters} ?= meters(Pid, Ctx),
        {ok, #{
            checks => Checks#{
                "registered" => success(true),
                "can_ping" => success(PingResult),
                "message_queue_state" => message_queue(Id, Info)
            },
            info => Info,
            metrics => Metrics,
            meters => Meters
        }}
    else
        _ ->
            {ok, #{
                checks => #{"registered" => success(false)},
                info => #{},
                metrics => #{},
                meters => #{}
            }}
    end.

-spec get_metrics(Id :: known_services(), Ctx :: #clouseau_ctx{}) ->
    {ok, metrics_result()} | {error, Reason :: term()}.

get_metrics(Id, #clouseau_ctx{} = Ctx) when ?is_known_id(Id) ->
    case whereis(Id, Ctx) of
        {ok, Pid} when is_pid(Pid) ->
            metrics(Pid);
        undefined ->
            {error, {not_running, Id}}
    end.

-spec get_meters(Id :: known_services(), Ctx :: #clouseau_ctx{}) ->
    {ok, meters_result()} | {error, Reason :: term()}.

get_meters(Id, #clouseau_ctx{} = Ctx) when ?is_known_id(Id) ->
    case whereis(Id, Ctx) of
        {ok, Pid} when is_pid(Pid) ->
            meters(Pid, Ctx);
        undefined ->
            {error, {not_running, Id}}
    end.

stop_service(Pid, #clouseau_ctx{} = Ctx) when is_pid(Pid) ->
    stop_service(Pid, Ctx, normal, ?TIMEOUT_IN_MS).

stop_service(Pid, #clouseau_ctx{}, Reason, TimeoutInMs) when is_pid(Pid) ->
    Tag = erlang:monitor(process, Pid),
    exit(Pid, Reason),
    receive
        {'DOWN', Tag, process, Pid, _Reason} ->
            ok
    after TimeoutInMs ->
        timeout
    end.

-spec restart_service(Id :: init | rex | cleanup | main | analyzer, #clouseau_ctx{}) ->
    {ok, Pid :: pid()} | {error, Reason :: term()}.
restart_service(Id, #clouseau_ctx{} = Ctx) when ?is_known_id(Id) ->
    case whereis(Id, Ctx) of
        {ok, Pid} when is_pid(Pid) ->
            stop_service(Pid, Ctx),
            check_ping(Id, Ctx),
            or_else(whereis(Id, Ctx), {error, {cannot_start, Id}});
        _ ->
            {error, {not_running, Id}}
    end.

-spec info(IdOrPid :: pid() | init | rex | cleanup | main | analyzer, #clouseau_ctx{}) ->
    {ok, Info :: #{string() => any()}} | {error, Reason :: term()}.
info(Id, #clouseau_ctx{} = Ctx) when ?is_known_id(Id) ->
    case rex_rpc({service, info, Id}, Ctx) of
        {ok, #process_info{properties = Map}} ->
            {ok, Map};
        {error, _} = Error ->
            Error
    end;
info(Pid, #clouseau_ctx{} = Ctx) when is_pid(Pid) ->
    case rex_rpc({service, info, Pid}, Ctx) of
        {ok, #process_info{properties = Map}} ->
            {ok, Map};
        {error, _} = Error ->
            Error
    end.

check_ping(Id, #clouseau_ctx{} = Ctx) when is_atom(Id) ->
    check_ping(Id, Ctx, ?PING_TIMEOUT_IN_MS).

check_ping(Id, #clouseau_ctx{remote = Node}, TimeoutInMs) when is_atom(Id) ->
    clouseau_utils:wait_value(
        fun() ->
            try gen_server:call({Id, Node}, ping, TimeoutInMs) of
                Result ->
                    Result
            catch
                _:_ ->
                    wait
            end
        end,
        pong,
        TimeoutInMs
    ).

%%====================================================================
%% Checks
%%====================================================================

message_queue(init, #{message_queue_len := N}) ->
    lte(N, 1);
message_queue(rex, #{message_queue_len := N}) ->
    lte(N, 1);
message_queue(analyzer, #{message_queue_len := N}) ->
    lte(N, 10);
message_queue(cleanup, #{message_queue_len := N}) ->
    lte(N, 10);
message_queue(main, #{message_queue_len := N}) ->
    lte(N, 20).

-spec metrics_to_check(known_services(), metrics_result()) -> check_result().
metrics_to_check(main, Metrics) ->
    {value, {_, Count}} = find_metric("opens.count", Metrics),
    {value, {_, Rate}} = find_metric("opens.fifteenMinutesRate", Metrics),
    #{
        "non_zero_rate" => gt(Rate, 0.0),
        "have_open_indexes" => gt(Count, 0)
    };
metrics_to_check(_Id, _Metrics) ->
    #{}.

%%====================================================================
%% Combinators
%%====================================================================

or_else(undefined, Else) ->
    Else;
or_else(Result, _Else) ->
    Result.

on_success({ok, Result}, Fun) when is_function(Fun) ->
    {ok, Fun(Result)};
on_success(Else, _Fun) ->
    Else.

%%====================================================================
%% Internal functions
%%====================================================================

whereis(Id, #clouseau_ctx{} = Ctx) ->
    case list(Ctx) of
        {ok, #{Id := Pid}} ->
            {ok, Pid};
        _ ->
            undefined
    end.

rex_rpc(Msg, #clouseau_ctx{} = Ctx) ->
    service_rpc(rex, Msg, Ctx).

main_rpc(Msg, #clouseau_ctx{} = Ctx, Timeout) ->
    service_rpc(main, Msg, Ctx, Timeout).

-spec metrics(Pid :: pid()) -> {ok, metrics_result()} | {error, any()}.
metrics(Pid) ->
    on_success(rpc(Pid, metrics), fun normalize_metrics/1).

-spec meters(Pid :: pid(), #clouseau_ctx{}) -> {ok, meters_result()} | {error, any()}.
meters(Pid, #clouseau_ctx{} = Ctx) when is_pid(Pid) ->
    on_success(rex_rpc({service, meters, Pid}, Ctx), fun normalize_meters/1).

normalize_meters(Meters) ->
    lists:foldl(
        fun(
            #meter_info{properties = #{meter_name := Name, tags := [Type | _], value := V}},
            #{} = Acc
        ) when
            is_list(Type) andalso is_atom(Name)
        ->
            MeterId = Type ++ "." ++ atom_to_list(Name),
            Acc#{MeterId => V}
        end,
        #{},
        Meters
    ).

rpc(Pid, Msg) ->
    rpc(Pid, Msg, ?TIMEOUT_IN_MS).

rpc(Pid, Msg, Timeout) when is_pid(Pid) ->
    try
        gen_server:call(Pid, Msg, Timeout)
    catch
        Kind:Reason ->
            {error, {Kind, Reason}}
    end.

service_rpc(Service, Msg, #clouseau_ctx{} = Ctx) ->
    service_rpc(Service, Msg, Ctx, ?TIMEOUT_IN_MS).

service_rpc(Service, Msg, #clouseau_ctx{remote = Node}, Timeout) ->
    try
        gen_server:call({Service, Node}, Msg, Timeout)
    catch
        Kind:Reason ->
            {error, {Kind, Reason}}
    end.

normalize_metrics(Metrics) ->
    fold_metric(fun normalize_metric/3, #{}, Metrics).

-spec normalize_metric(Key :: atom(), supported_metric() | number(), #{}) -> metrics_result().
normalize_metric(Key, #{fifteenMinutesRate := Rate, count := Count, rateUnit := Unit}, Acc) when
    is_number(Rate) andalso is_number(Count)
->
    RateId = atom_to_list(Key) ++ ".fifteenMinutesRate",
    CountId = atom_to_list(Key) ++ ".count",
    Acc#{
        RateId => {binary_to_list(Unit), Rate},
        CountId => {"events", Count}
    };
normalize_metric(Key, Count, Acc) when is_number(Count) ->
    Acc#{atom_to_list(Key) => {"events", Count}}.

-spec fold_metric(
    Fun :: fun((atom(), supported_metric() | number(), #{}) -> metrics_result()),
    Acc :: #{},
    Metrics :: #{atom() => supported_metric() | number()}
) -> metrics_result().
%% wrapper function to make eqwalizer happy
fold_metric(Fun, Acc, Metrics) when is_map(Acc) ->
    % eqwalizer:ignore incompatible_types - prefer our spec since lists:foldl uses term()
    lists:foldl(fun({K, V}, A) -> Fun(K, V, A) end, Acc, maps:to_list(Metrics)).

find_metric(Prefix, Metrics) ->
    maps:fold(
        fun
            (_, _, {value, _} = Result) ->
                Result;
            (K, V, Acc) ->
                case string:prefix(K, Prefix) of
                    nomatch ->
                        Acc;
                    _ ->
                        {value, V}
                end
        end,
        false,
        Metrics
    ).

gte(V, T) when V >= T ->
    success(format("V>=T: ~p >= ~p", [V, T]));
gte(V, T) ->
    failure(format("V>=T: ~p >= ~p", [V, T])).

gt(V, T) when V > T ->
    success(format("V>T: ~p > ~p", [V, T]));
gt(V, T) ->
    failure(format("V>T: ~p > ~p", [V, T])).

lte(V, T) when V =< T ->
    success(format("V=<T: ~p =< ~p", [V, T]));
lte(V, T) ->
    failure(format("V=<T: ~p =< ~p", [V, T])).

-spec success(Result :: any()) -> {success, string()}.
success(true) ->
    {success, "true"};
success(false) ->
    {success, "false"};
success(String) when is_list(String) ->
    {success, format("~s", [String])};
success(Result) ->
    {success, format("~p", [Result])}.

failure(String) when is_list(String) ->
    {failure, format("~s", [String])};
failure(Result) ->
    {success, format("~p", [Result])}.

-spec format(Format :: string(), Arg :: [any()]) -> string().
format(Format, Args) ->
    lists:flatten(io_lib:format(Format, Args)).
