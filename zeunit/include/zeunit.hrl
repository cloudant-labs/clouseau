-include_lib("eunit/include/eunit.hrl").

-define(COOKIE, cookie).
-define(HOST, "127.0.0.1").
-define(NodeZ, list_to_atom("clouseau1@" ++ ?HOST)).

%% Test DEFinition For Each (fixtures which use foreach)”
-ifndef(TDEF_FE).
-define(TDEF_FE(Name), fun(Arg) -> {atom_to_list(Name), ?_test(Name(Arg))} end).
-define(TDEF_FE(Name, Timeout), fun(Arg) ->
    {atom_to_list(Name), {timeout, Timeout, ?_test(Name(Arg))}}
end).
-endif.

%% Test DEFinition For Each X (fixtures which use foreachx)”
-define(TDEF_FEX(Name),
    {Name, fun(Name, Args) -> ?_test(Name(Name, Args)) end}).

-record(top_docs, {
    update_seq,
    total_hits,
    hits,
    counts,
    ranges
}).
