-module(clouseau_io).

-export([
    format_ok/2,
    format_nok/2,
    format_warn/2,

    ok/1,
    nok/1,
    warn/1,

    ok/2,
    nok/2,
    warn/2,

    print_table/2,
    print_report/1,
    print_report/2
]).

-define(ANSI_COLORED, "\x1b[").
-define(ANSI_RESET, "\x1b[0m").
-define(ANSI_RED, "31").
-define(ANSI_GREEN, "32").
-define(ANSI_YELLOW, "33").

format_ok(Format, Args) ->
    ok(success, io_lib:format(Format, Args)).

format_nok(Format, Args) ->
    nok(failure, io_lib:format(Format, Args)).

format_warn(Format, Args) ->
    warn(warn, io_lib:format(Format, Args)).

ok(Text) ->
    ok(success, Text).

ok(Tag, Text) ->
    log_colored(?ANSI_GREEN, Tag, Text),
    ok.

nok(Text) ->
    nok(failure, Text).

nok(Tag, Text) ->
    log_colored(?ANSI_RED, Tag, Text),
    nok.

warn(Text) ->
    warn(warn, Text).

warn(Tag, Text) ->
    log_colored(?ANSI_YELLOW, Tag, Text),
    warn.

log_colored(Color, Status, Text) ->
    io:fwrite("[~s~s~s] ~s~n", [?ANSI_COLORED ++ Color ++ "m", Status, ?ANSI_RESET, Text]).

%% Pretty print functions

%% Limitations:
%%   - The first column has to be specified as {Width, left, Something}
%% The TableSpec is a list of either:
%%   - {Value}
%%   - {Width, Align, Value}
%% Align is one of the following:
%%  - left
%%  - centre
%%  - right
print_table(Rows, TableSpec) ->
    print_header(TableSpec),
    lists:foreach(
        fun({Id, Props}) ->
            io:format("~s~n", [table_row(Id, 2, Props, TableSpec)])
        end,
        Rows
    ),
    print_footer(TableSpec),
    ok.

print_report(Report) ->
    print_report(Report, [
        {auto, left, info},
        {auto, right, value}
    ]).

print_report(Report, TableSpec) ->
    Spec = apply_width(Report, TableSpec),
    print_header(TableSpec),
    lists:map(
        fun({InfoKey, Value}) ->
            io:format("~s~n", [
                table_row(InfoKey, 2, [{InfoKey, Value}], rename_value(InfoKey, Spec))
            ])
        end,
        Report
    ),
    print_footer(TableSpec).

format(Spec) ->
    Fields = [format_value(Format) || Format <- Spec],
    [$| | string:join(Fields, "|") ++ "|"].

format_value({Value}) -> term2str(Value);
format_value({Width, Align, Value}) -> string:Align(term2str(Value), Width).

bind_value({K}, Props) when is_list(Props) ->
    {element(2, value(K, Props))};
bind_value({Width, Align, K}, Props) when is_list(Props) ->
    {Width, Align, element(2, value(K, Props))}.

value(Key, Props) ->
    lists:keyfind(Key, 1, Props).

term2str(Atom) when is_atom(Atom) -> atom_to_list(Atom);
term2str(Binary) when is_binary(Binary) -> binary_to_list(Binary);
term2str(Integer) when is_integer(Integer) -> integer_to_list(Integer);
term2str(String) when is_list(String) -> lists:flatten(String);
term2str(Term) -> iolist_to_list(io_lib:format("~p", [Term])).

table_row(Key, Indent, Props, [{KeyWidth, Align, _} | Spec]) ->
    Values = [bind_value(Format, Props) || Format <- Spec],
    KeyStr = string:Align(term2str(Key), KeyWidth - Indent),
    [$|, string:copies(" ", Indent), KeyStr | format(Values)].

value_width(Props) ->
    lists:foldl(
        fun({_, V}, Max) ->
            max(Max, io_lib:chars_length(format_value({V})))
        end,
        0,
        Props
    ).

key_width(Props) ->
    lists:foldl(
        fun({Key, _}, Max) ->
            max(Max, io_lib:chars_length(term2str(Key)))
        end,
        0,
        Props
    ).

apply_width(Props, TableSpec) ->
    lists:map(
        fun
            ({auto, Align, info}) ->
                {key_width(Props) + 2, Align, info};
            ({auto, Align, Key}) ->
                {value_width(Props) + 2, Align, Key};
            (Else) ->
                Else
        end,
        TableSpec
    ).

rename_value(InfoKey, TableSpec) ->
    lists:map(
        fun
            ({_, _, info} = S) ->
                S;
            ({Width, Align, _}) ->
                {Width, Align, InfoKey};
            (Else) ->
                Else
        end,
        TableSpec
    ).

print_header(TableSpec) ->
    Formatted = format(TableSpec),
    Underline = string:copies("-", length(Formatted)),
    io:format("~n~s~n~s~n~s~n", [Underline, format(TableSpec), Underline]).

print_footer(TableSpec) ->
    Formatted = format(TableSpec),
    Underline = string:copies("-", length(Formatted)),
    io:format("~s~n~n", [Underline]).

iolist_to_list(List) ->
    binary_to_list(iolist_to_binary(List)).
