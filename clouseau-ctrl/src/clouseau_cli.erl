-module(clouseau_cli).

-export([
    help/1,
    run/2
]).

-ifdef(TEST).
-export([
    normalize_args/1,
    read_config/1,
    resolve_value/3,
    to_format/1
]).
-endif.

parser_options() ->
    #{progname => clouseau_cli}.

spec(Services) ->
    spec(Services, runtime).

spec(Services, Mode) ->
    #{
        help => "Utility to control 'clouseau' process",
        arguments => arguments(Mode),
        commands => #{
            "service" => #{
                help => "Service management",
                commands => #{
                    "list" => #{
                        help => "List all services",
                        handler => {clouseau_ctrl, service_list}
                    },
                    "restart" => #{
                        help => "Restart a service",
                        arguments => [
                            #{name => id, type => {atom, Services}, help => <<"Service ID">>}
                        ],
                        handler => {clouseau_ctrl, service_restart}
                    },
                    "info" => #{
                        help => "Get info about a service",
                        arguments => [
                            #{name => id, type => {atom, Services}, help => <<"Service ID">>}
                        ],
                        handler => {clouseau_ctrl, service_info}
                    },
                    "metrics" => #{
                        help => "Get metrics of a service",
                        arguments => [
                            #{name => id, type => {atom, Services}, help => <<"Service ID">>}
                        ],
                        handler => {clouseau_ctrl, service_metrics}
                    },
                    "check" => #{
                        help => "Check status of a service",
                        arguments => [
                            #{name => id, type => {atom, Services}, help => <<"Service ID">>}
                        ],
                        handler => {clouseau_ctrl, service_check}
                    },
                    "report" => #{
                        help => "Print health report of a service",
                        arguments => [
                            #{name => id, type => {atom, Services}, help => <<"Service ID">>}
                        ],
                        handler => {clouseau_ctrl, service_report}
                    }
                }
            }
        }
    }.

arguments(Mode) ->
    [
        #{
            name => script_name,
            long => "script_name",
            type => string,
            default => "clouseau_cli",
            help => hidden
        },
        #{
            name => script_path,
            long => "script_path",
            type => string,
            default => script_path(),
            help => hidden
        },
        #{
            name => script_dir,
            long => "script_dir",
            type => string,
            default => filename:dirname(script_path()),
            help => hidden
        },
        #{
            name => config,
            short => $g,
            long => "config",
            type => string,
            default => default_config_path(),
            help => "Path to clouseau-ctrl config file. Can also be set via CLOUSEAU_CTRL_CONFIG."
        },
        maybe_default(
            #{
                name => name,
                short => $n,
                long => "name",
                type => string,
                help =>
                    "The name of clouseau node (e.g. 'clouseau1'). Resolution order: CLI, CLOUSEAU_NODE_NAME, config file."
            },
            false,
            Mode
        ),
        maybe_default(
            #{
                name => cookie,
                short => $c,
                long => "cookie",
                type => string,
                help =>
                    "Erlang cookie to use for connecting. Resolution order: CLI, CLOUSEAU_COOKIE, config file, ~/.erlang.cookie."
            },
            false,
            Mode
        ),
        maybe_default(
            #{
                name => host,
                short => $h,
                long => "host",
                type => string,
                help =>
                    "Host name of the clouseau node (e.g. '127.0.0.1'). Resolution order: CLI, CLOUSEAU_HOST, config file, 127.0.0.1."
            },
            false,
            Mode
        ),
        maybe_default(
            #{
                name => format,
                short => $f,
                long => "format",
                type => atom,
                help =>
                    "Output format: 'json' or 'table'. Resolution order: CLI, CLOUSEAU_FORMAT, config file, table.",
                choices => [json, table]
            },
            false,
            Mode
        )
    ].

help(Services) ->
    argparse:help(spec(Services, help), parser_options()).

run(["--help"], Services) ->
    io:format("~s", [help(Services)]);
run(["help"], Services) ->
    io:format("~s", [help(Services)]);
run(Args, Services) ->
    {ok, ParsedArgs, _Path, CommandSpec} = argparse:parse(Args, spec(Services), parser_options()),
    clouseau_ctrl:dispatch(
        normalize_args(
            maps:merge(ParsedArgs, maps:with([handler], CommandSpec))
        )
    ).

%%====================================================================
%% Internal functions
%%====================================================================

default_config_path() ->
    case os:getenv("CLOUSEAU_CTRL_CONFIG") of
        false ->
            filename:join("/etc/clouseau", "clouseau-ctrl-config.eterm");
        Path ->
            Path
    end.

maybe_default(Argument, Default, runtime) ->
    Argument#{default => Default};
maybe_default(Argument, _Default, help) ->
    Argument.

script_path() ->
    case catch escript:script_name() of
        {'EXIT', _} ->
            "clouseau_cli";
        [] ->
            "clouseau_cli";
        Path ->
            Path
    end.

normalize_args(ParsedArgs) ->
    ConfigPath = maps:get(config, ParsedArgs, default_config_path()),
    Config = read_config(ConfigPath),
    Defaults = #{
        config => ConfigPath,
        name => resolve_value(
            maps:get(name, ParsedArgs, false),
            os:getenv("CLOUSEAU_NODE_NAME"),
            maps:get(name, Config, undefined)
        ),
        cookie => resolve_value(
            maps:get(cookie, ParsedArgs, false),
            os:getenv("CLOUSEAU_COOKIE"),
            maps:get(cookie, Config, read_erlang_cookie())
        ),
        host => resolve_value(
            maps:get(host, ParsedArgs, false),
            os:getenv("CLOUSEAU_HOST"),
            maps:get(host, Config, "127.0.0.1")
        ),
        format => to_format(
            resolve_value(
                maps:get(format, ParsedArgs, false),
                os:getenv("CLOUSEAU_FORMAT"),
                maps:get(format, Config, table)
            )
        )
    },
    FilteredParsedArgs = maps:filter(fun(_K, V) -> V =/= false end, ParsedArgs),
    maps:merge(Defaults, FilteredParsedArgs).

to_format("json") ->
    json;
to_format("table") ->
    table;
to_format(json) ->
    json;
to_format(table) ->
    table;
to_format(_) ->
    table.

resolve_value(false, false, Default) ->
    Default;
resolve_value(false, undefined, Default) ->
    Default;
resolve_value(false, EnvValue, _Default) ->
    EnvValue;
resolve_value(undefined, false, Default) ->
    Default;
resolve_value(undefined, undefined, Default) ->
    Default;
resolve_value(undefined, EnvValue, _Default) ->
    EnvValue;
resolve_value(Value, _EnvValue, _Default) ->
    Value.

read_config(Path) ->
    case file:consult(Path) of
        {ok, [Config]} when is_map(Config) ->
            Config;
        _ ->
            io:format(standard_error, "Warning: Unable to read config file: ~s~n", [Path]),
            #{}
    end.

read_erlang_cookie() ->
    case os:getenv("HOME") of
        false ->
            undefined;
        Home ->
            CookiePath = filename:join(Home, ".erlang.cookie"),
            case file:read_file(CookiePath) of
                {ok, Cookie} ->
                    string:trim(binary_to_list(Cookie));
                _ ->
                    undefined
            end
    end.
