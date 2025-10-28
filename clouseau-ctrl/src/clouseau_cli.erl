-module(clouseau_cli).

-export([
    help/1,
    run/2
]).

parser_options() ->
    #{progname => clouseau_cli}.

spec(Services) ->
    #{
        help => "Utility to control 'clouseau' process",
        arguments => [
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
                default => escript:script_name(),
                help => hidden
            },
            #{
                name => script_dir,
                long => "script_dir",
                type => string,
                default => filename:dirname(escript:script_name()),
                help => hidden
            },
            #{
                name => name,
                short => $n,
                long => "name",
                type => string,
                default => getenv("CLOUSEAU_NODE_NAME"),
                help => "The name of clouseau node (e.g. 'clouseau1')"
            },
            #{
                name => cookie,
                short => $c,
                long => "cookie",
                type => string,
                default => getenv("CLOUSEAU_COOKIE"),
                help => "Erlang cookie to use for connecting"
            },
            #{
                name => host,
                short => $h,
                long => "host",
                type => string,
                default => "127.0.0.1",
                help => "Host name of the clouseau node (e.g. '127.0.0.1')"
            },
            #{
                name => format,
                short => $f,
                long => "format",
                type => atom,
                default => table,
                help => "Output format: 'json' or 'table'",
                choices => [json, table]
            }
        ],

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

help(Services) ->
    argparse:help(spec(Services), parser_options()).

run(["--help"], Services) ->
    io:format("~s", [help(Services)]);
run(["help"], Services) ->
    io:format("~s", [help(Services)]);
run(Args, Services) ->
    argparse:run(Args, spec(Services), parser_options()).

%%====================================================================
%% Internal functions
%%====================================================================

getenv(Name) ->
    case os:getenv(Name) of
        false ->
            undefined;
        Value ->
            Value
    end.
