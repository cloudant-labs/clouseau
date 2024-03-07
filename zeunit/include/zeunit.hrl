-include_lib("eunit/include/eunit.hrl").

-define(COOKIE, cookie).
-define(HOST, "127.0.0.1").
-define(NodeT, list_to_atom("node1@" ++ ?HOST)).
-define(NodeZ, list_to_atom("clouseau1@" ++ ?HOST)).

-ifndef(TDEF_FE).
-define(TDEF_FE(Name), fun(Arg) -> {atom_to_list(Name), ?_test(Name(Arg))} end).
-define(TDEF_FE(Name, Timeout), fun(Arg) ->
    {atom_to_list(Name), {timeout, Timeout, ?_test(Name(Arg))}}
end).
-endif.

-record(top_docs, {
    update_seq,
    total_hits,
    hits,
    counts,
    ranges
}).
