-module(clouseau_rpc_tests).
-include("zeunit.hrl").

-define(Analyzer, <<"standard">>).
-define(Ddoc, <<"_design/ddoc">>).
-define(Signature, <<"d46ff3d065da8e84d4e7fec9d63b99f9">>).

clouseau_rpc_without_docs_test_() ->
    {"clouseau rpc tests without docs",
        {setup, fun setup/0, fun teardown/1,
            with([
                ?TDEF(t_get_update_seq),
                ?TDEF(t_set_purge_seq),
                ?TDEF(t_get_purge_seq),
                ?TDEF(t_delete),
                ?TDEF(t_commit),

                ?TDEF(t_info),
                ?TDEF(t_search),
                ?TDEF(t_group1)
            ])}}.

clouseau_rpc_simple_test_() ->
    {"clouseau rpc simple tests",
        {setup, fun setupDb/0, fun teardown/1,
            with([
                ?TDEF(t_disk_size),
                ?TDEF(t_get_root_dir),
                ?TDEF(t_open_index),
                ?TDEF(t_analyze),
                ?TDEF(t_version)
            ])}}.

clouseau_rpc_gen_server_cast_test_() ->
    {"clouseau rpc gen_server:cast tests",
        {setup, fun setupDb/0, fun teardown/1,
            with([
                ?TDEF(t_cleanup_1),
                ?TDEF(t_cleanup_2),
                ?TDEF(t_rename)
            ])}}.

t_get_update_seq(IndexPid) ->
    {ok, Seq} = clouseau_rpc:get_update_seq(IndexPid),
    ?assertEqual(0, Seq).

t_set_purge_seq(IndexPid) ->
    {ok, Seq} = clouseau_rpc:get_update_seq(IndexPid),
    Response = clouseau_rpc:set_purge_seq(IndexPid, Seq),
    ?assertEqual(ok, Response).

t_get_purge_seq(IndexPid) ->
    {ok, Seq} = clouseau_rpc:get_purge_seq(IndexPid),
    ?assertEqual(0, Seq).

t_delete(IndexPid) ->
    Response = clouseau_rpc:delete(IndexPid, ?Ddoc),
    ?assertEqual(ok, Response).

t_commit(IndexPid) ->
    NewCommitSeq = 1,
    Response = clouseau_rpc:commit(IndexPid, NewCommitSeq),
    ?assertEqual(ok, Response).

t_info(IndexPid) ->
    {ok, Info} = clouseau_rpc:info(IndexPid),
    Expected = [
        {disk_size, 0},
        {doc_count, 0},
        {doc_del_count, 0},
        {pending_seq, 1},
        {committed_seq, 0},
        {purge_seq, 0}
    ],
    ?assertEqual(Expected, Info).

t_search(IndexPid) ->
    Args = [
        {query, <<"*:*">>},
        {partition, nil},
        {limit, 25},
        {refresh, true},
        {'after', nil},
        {sort, relevance},
        {include_fields, nil},
        {counts, nil},
        {ranges, nil},
        {drilldown, []},
        {highlight_fields, nil},
        {highlight_pre_tag, <<"<em>">>},
        {highlight_post_tag, <<"</em>">>},
        {highlight_number, 1},
        {highlight_size, 0}
    ],
    {ok, Response} = clouseau_rpc:search(IndexPid, Args),
    Expected = {top_docs, 0, 0, [], undefined, undefined},
    ?assertEqual(Expected, Response).

t_group1(IndexPid) ->
    Query = <<"*:*">>,
    GroupBy = <<"title">>,
    Refresh = true,
    Sort = relevance,
    Offset = 0,
    Limit = 10,
    {ok, Response} = clouseau_rpc:group1(IndexPid, Query, GroupBy, Refresh, Sort, Offset, Limit),
    ?assertEqual([], Response).

t_disk_size({_, _, Path}) ->
    {ok, [{disk_size, Size}]} = clouseau_rpc:disk_size(Path),
    ?assertEqual(0, Size).

t_get_root_dir(_) ->
    {ok, Path} = clouseau_rpc:get_root_dir(),
    ?assertNotEqual(nomatch, binary:match(Path, <<"target">>)).

t_open_index({_, _, Path}) ->
    {ok, IndexPid} = clouseau_rpc:open_index(self(), Path, ?Analyzer),
    ?assert(is_pid(IndexPid)).

t_analyze(_) ->
    Text1 = <<"ablanks">>,
    Text2 = <<"renovations.com">>,
    Text = <<Text1/binary, "@", Text2/binary>>,
    {ok, TokensStandard} = clouseau_rpc:analyze(?Analyzer, Text),
    ?assertEqual([Text1, Text2], TokensStandard),
    {ok, TokensKeyword} = clouseau_rpc:analyze(<<"keyword">>, Text),
    ?assertEqual([Text], TokensKeyword).

t_version(_) ->
    {ok, Version} = clouseau_rpc:version(),
    ?assertEqual(
        match,
        re:run(Version, "^[0-9]+\\.[0-9]+\\.[0-9]+$", [{capture, none}]),
        "Expected version to be in A.B.C format, where A, B and C are integers."
    ).

t_cleanup_1({_, ShardsDbName, _}) ->
    ?assertEqual(ok, clouseau_rpc:cleanup(ShardsDbName)).

t_cleanup_2({DbName, _, _}) ->
    ?assertEqual(ok, clouseau_rpc:cleanup(DbName, [?Signature])).

t_rename({DbName, _, _}) ->
    ?assertEqual(ok, clouseau_rpc:rename(DbName)).

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%
setupDb() ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    DbName = test_util:tempdb(),
    ShardsDbName = <<"shards/00000000-ffffffff/", DbName/binary, ".1730151232">>,
    Path = <<ShardsDbName/binary, "/", ?Signature/binary>>,
    {DbName, ShardsDbName, Path}.

setup() ->
    {_, _, Path} = setupDb(),
    {ok, IndexPid} = clouseau_rpc:open_index(self(), Path, ?Analyzer),
    ?assert(is_pid(IndexPid)),
    IndexPid.

teardown(_) ->
    ok.
