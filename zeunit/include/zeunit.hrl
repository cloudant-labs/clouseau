-include_lib("eunit/include/eunit.hrl").
-compile([{nowarn_unused_function, [{with, 1}]}]).

-define(COOKIE, cookie).
-define(HOST, "127.0.0.1").
-define(NodeZ, list_to_atom("clouseau1@" ++ ?HOST)).

%% Test DEFinition
-ifndef(TDEF).
-define(TDEF(Name), {atom_to_list(Name), fun Name/1}).
-define(TDEF(Name, Timeout), {atom_to_list(Name), Timeout, fun Name/1}).
-endif.

%% Test DEFinition For Each (fixtures which use foreach)”
-ifndef(TDEF_FE).
-define(TDEF_FE(Name), fun(Arg) -> {atom_to_list(Name), ?_test(Name(Arg))} end).
-define(TDEF_FE(Name, Timeout), fun(Arg) ->
    {atom_to_list(Name), {timeout, Timeout, ?_test(Name(Arg))}}
end).
-endif.

%% Test DEFinition For Each X (fixtures which use foreachx)”
-define(TDEF_FEX(Name),
    {Name, fun(Name, Args) -> {atom_to_list(Name), ?_test(Name(Name, Args))} end}
).

-define(TDEF_FEX(With, Name),
    {With, fun(With, Args) -> {?format("~s - ~p", [Name, With]), ?_test(Name(With, Args))} end}
).

-define(TDEF_FEXN(With, Name),
    {{With, Name}, fun({With, _}, Args) ->
        {?format("~s - ~p", [Name, With]), ?_test(Name(With, Args))}
    end}
).

-define(format(Fmt, Args),
    lists:flatten(io_lib:format(Fmt, Args))
).

%% Auxilary macro to simplify definition of other asserts
-ifndef(_assertGuard).
-define(_assertGuard(Name, Guard, Expr), begin
    ((fun() ->
        case (Expr) of
            Guard ->
                ok;
            X__V ->
                erlang:error(
                    {
                        Name, [
                            {module, ?MODULE},
                            {line, ?LINE},
                            {expression, (??Expr)},
                            {pattern, (??Guard)},
                            {value, X__V}
                        ]
                    }
                )
        end
    end)())
end).
-endif.

%% Assert that the value is non negative integer() [0..].
-ifndef(assertNonNegInteger).
-define(assertNonNegInteger(Expr),
    ?_assertGuard(assertNonNegInteger_failed, ??__V when is_integer(??__V) andalso ??__V >= 0, Expr)
).
-endif.
-ifndef(_assertNonNegInteger).
-define(_assertNonNegInteger(Expr), ?_test(?assertNonNegInteger(Expr))).
-endif.

%% Assert that the value is non negative float() [0.0..].
-ifndef(assertNonNegFloat).
-define(assertNonNegFloat(Expr),
    ?_assertGuard(assertNonNegFloat_failed, ??__V when is_float(??__V) andalso ??__V >= 0.0, Expr)
).
-endif.
-ifndef(_assertNonNegFloat).
-define(_assertNonNegFloat(Expr), ?_test(?assertNonNegFloat(Expr))).
-endif.

%% Assert that the value is non negative number() [0..].
-ifndef(assertNonNegNumber).
-define(assertNonNegNumber(Expr),
    ?_assertGuard(assertNonNegNumber_failed, ??__V when is_number(??__V) andalso ??__V >= 0, Expr)
).
-endif.
-ifndef(_assertNonNegNumber).
-define(_assertNonNegNumber(Expr), ?_test(?assertNonNegNumber(Expr))).
-endif.

-record(top_docs, {
    update_seq,
    total_hits,
    hits,
    counts,
    ranges
}).

with(Tests) ->
    fun(ArgsTuple) ->
        lists:map(
            fun
                ({Name, Fun}) ->
                    {Name, ?_test(Fun(ArgsTuple))};
                ({Name, Timeout, Fun}) ->
                    {Name, {timeout, Timeout, ?_test(Fun(ArgsTuple))}}
            end,
            Tests
        )
    end.

-type error(_Reason) :: no_return().

-type assert_opt() ::
    {module, atom()}
    | {line, pos_integer()}
    | {expression, string()}
    | {pattern, string()}
    | {value, any()}.

-type assert_error() :: {Name :: atom(), [assert_opt()]}.
