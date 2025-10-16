% To run only this suite use
% ```
% make zeunit suites=clouseau_tests
% ```

-module(clouseau_tests).
-feature(maybe_expr, enable).

-include("zeunit.hrl").
-include_lib("eunit/include/eunit.hrl").

-define(Analyzer, <<"standard">>).

-define(INIT_SERVICE, init).
-define(TIMEOUT_IN_MS, 1000).

-record(index, {
    idx :: pos_integer(),
    path :: binary(),
    signature :: string(),
    pid = nil :: pid() | nil
}).

-record(context, {
    dbname :: binary(),
    shard_name :: binary(),
    indexes :: #{pos_integer() => #index{}},
    echo_pid :: pid()
}).

-define(FIELDS, [
    {<<"$fieldnames">>, <<"$default">>, []},
    {<<"$fieldnames">>, <<"age_3anumber">>, []},
    {<<"$fieldnames">>, <<"favorites._5b_5d_3alength">>, []},
    {<<"$fieldnames">>, <<"favorites._5b_5d_3astring">>, []},
    {<<"$fieldnames">>, <<"location.address.street_3astring">>, []},
    {<<"$fieldnames">>, <<"location.state_3astring">>, []},
    {<<"$default">>, <<"North Dakota">>, []},
    {<<"$default">>, <<"Brightwater Avenue">>, []},
    {<<"$default">>, <<"Erlang">>, []},
    {<<"$default">>, <<"Python">>, []},
    {<<"$default">>, <<"Lisp">>, []},
    %% favorites.[]:string
    {<<"favorites._5b_5d_3astring">>, <<"Lisp">>, []},
    %% favorites.[]:string
    {<<"favorites._5b_5d_3astring">>, <<"Python">>, []},
    %% favorites.[]:string
    {<<"favorites._5b_5d_3astring">>, <<"Erlang">>, []},
    %% favorites.[]:length
    {<<"favorites._5b_5d_3alength">>, 3, []},
    %% location.address.street:string
    {<<"location.address.street_3astring">>, <<"Brightwater Avenue">>, []},
    %% location.state:string
    {<<"location.state_3astring">>, <<"North Dakota">>, []},
    %% age:number
    {<<"age_3anumber">>, 51, []}
]).

clouseau_test_() ->
    {"clouseau cleanup service tests",
        {foreachx, fun setup/1, fun teardown/2, [
            ?TDEF_FEXN(ioq, t_cleanup_1),
            ?TDEF_FEXN(ioq, t_cleanup_2),
            ?TDEF_FEXN(ioq, t_rename),

            ?TDEF_FEXN(gen_server, t_cleanup_1),
            ?TDEF_FEXN(gen_server, t_cleanup_2),
            ?TDEF_FEXN(gen_server, t_rename)
        ]}}.

t_cleanup_1(
    _Kind, #context{echo_pid = EchoService, dbname = DBName, shard_name = ShardsDbName} = Ctx
) ->
    ResultBeforeCleanup = get_files_grouped_by_index(Ctx),
    ?assertMatch(
        {ok, _},
        ResultBeforeCleanup,
        ?format("Expected to be able to get indexes for database, got: ~p", [ResultBeforeCleanup])
    ),
    {ok, {_BeforeDir, ListOfFilesBeforeCleanup}} = ResultBeforeCleanup,
    #index{signature = Signature1} = get_index(1, Ctx),
    FilesBeforeCleanupIndex = maps:get(Signature1, ListOfFilesBeforeCleanup, no_files),
    ?assertMatch(["_0.fdt", "_0.fdx" | _LockFile], lists:sort(FilesBeforeCleanupIndex)),
    ?assertEqual(ok, clouseau_rpc:cleanup(ShardsDbName)),
    DBNameSize = byte_size(DBName),
    ?assertMatch(
        {ok, <<"/shards/00000000-ffffffff/", DBName:DBNameSize/binary, ".", _/binary>>},
        wait_folder_deletion(EchoService, ShardsDbName),
        "Expect deletion of an index folder"
    ),
    ok.

t_cleanup_2(_Kind, #context{} = Ctx) ->
    ResultBeforeCleanup = get_files_grouped_by_index(Ctx),
    ?assertMatch(
        {ok, _},
        ResultBeforeCleanup,
        ?format("Expected to be able to get indexes for database, got: ~p", [ResultBeforeCleanup])
    ),
    {ok, {_BeforeDir, ListOfFilesBeforeCleanup}} = ResultBeforeCleanup,
    ?assertEqual(
        2,
        maps:size(ListOfFilesBeforeCleanup),
        ?format("Expected to find two folders, got ~p, here they are ~p", [
            maps:size(ListOfFilesBeforeCleanup), ListOfFilesBeforeCleanup
        ])
    ),

    #index{signature = Signature1} = get_index(1, Ctx),
    FilesBeforeCleanupForIndex1 = maps:get(Signature1, ListOfFilesBeforeCleanup, no_files),
    ?assert(
        is_list(FilesBeforeCleanupForIndex1),
        ?format("Expected to find files for first index (~p) among: ~p", [
            Signature1, FilesBeforeCleanupForIndex1
        ])
    ),
    ?assertMatch(["_0.fdt", "_0.fdx" | _LockFile], lists:sort(FilesBeforeCleanupForIndex1)),

    #index{signature = Signature2} = get_index(2, Ctx),
    FilesBeforeCleanupForIndex2 = maps:get(Signature2, ListOfFilesBeforeCleanup, no_files),
    ?assert(
        is_list(FilesBeforeCleanupForIndex2),
        ?format("Expected to find files for first index (~p) among: ~p", [
            Signature1, FilesBeforeCleanupForIndex2
        ])
    ),
    ?assertMatch(["_0.fdt", "_0.fdx" | _LockFile], lists:sort(FilesBeforeCleanupForIndex2)),

    ResultCleanup = cleanup_index(1, Ctx),
    ?assertMatch(
        {ok, {_, #{Signature2 := _}}},
        ResultCleanup,
        ?format("Expected to still have ~p in ~p", [Signature2, ResultCleanup])
    ),
    {ok, {_, #{Signature2 := ResultCleanupFiles}}} = ResultCleanup,
    ?assertMatch(["_0.fdt", "_0.fdx" | _LockFile], lists:sort(ResultCleanupFiles)),

    ResultAfterCleanup = get_files_grouped_by_index(Ctx),
    ?assertMatch(
        {ok, _},
        ResultAfterCleanup,
        ?format("Expected to be able to get indexes for database, got: ~p", [ResultAfterCleanup])
    ),
    {ok, {_AfterDir, ListOfFilesAfterCleanup}} = ResultAfterCleanup,
    ?assertEqual(
        1,
        maps:size(ListOfFilesAfterCleanup),
        ?format("Expected to find one folder, got ~p, here they are ~p", [
            maps:size(ListOfFilesBeforeCleanup), ListOfFilesBeforeCleanup
        ])
    ),

    FilesAfterCleanupForIndex1 = maps:get(Signature1, ListOfFilesAfterCleanup, no_files),
    ?assertEqual(
        no_files,
        FilesAfterCleanupForIndex1,
        ?format("Expected to not find files for first index (~p) among: ~p", [
            Signature1, FilesAfterCleanupForIndex1
        ])
    ),

    FilesAfterCleanupForIndex2 = maps:get(Signature2, ListOfFilesBeforeCleanup, no_files),
    ?assert(
        is_list(FilesAfterCleanupForIndex2),
        ?format("Expected to find files for first index (~p) among: ~p", [
            Signature1, FilesAfterCleanupForIndex2
        ])
    ),
    ?assertMatch(["_0.fdt", "_0.fdx" | _LockFile], lists:sort(FilesAfterCleanupForIndex2)),
    ok.

t_rename(_, #context{echo_pid = EchoService, dbname = DBName, shard_name = ShardsDbName} = Ctx) ->
    ResultBeforeRename = get_files_grouped_by_index(Ctx),
    ?assertMatch(
        {ok, _},
        ResultBeforeRename,
        ?format("Expected to be able to get indexes for database, got: ~p", [ResultBeforeRename])
    ),
    {ok, {BeforeDir, ListOfFilesBeforeRename}} = ResultBeforeRename,
    ?assertEqual(2, maps:size(ListOfFilesBeforeRename)),
    ?assertEqual(ok, clouseau_rpc:rename(ShardsDbName)),
    DBNameSize = byte_size(DBName),
    ?assertMatch(
        {ok, <<"/shards/00000000-ffffffff/", DBName:DBNameSize/binary, ".", _/binary>>},
        wait_folder_deletion(EchoService, ShardsDbName),
        "Expect deletion of an index folder"
    ),

    %% wait_folder_deletion(EchoService, ShardsDbName),
    ResultAfterRename = get_files_grouped_by_index(Ctx),
    ?assertMatch(
        {list_files, {error, {notDirectoryException, _, _}}},
        ResultAfterRename,
        ?format("Expected directory to be renamed and not present at previous location, got: ~p", [
            ResultAfterRename
        ])
    ),
    ResultRenamedFiles = get_renamed_files_grouped_by_index(Ctx),
    ?assertMatch(
        {ok, _},
        ResultRenamedFiles,
        ?format("Expected to be able to get renamed files for database, got: ~p", [
            ResultRenamedFiles
        ])
    ),
    {ok, {RenamedDir, ListOfRenamedFiles}} = ResultRenamedFiles,
    ?assertNotEqual(
        BeforeDir,
        RenamedDir,
        ?format("Expected directory name to be different, got: before = ~p, after = ~p", [
            BeforeDir, RenamedDir
        ])
    ),
    ?assertEqual(
        ListOfFilesBeforeRename,
        ListOfRenamedFiles,
        ?format("Expected the same files in renamed directory, got: before = ~p, after = ~p", [
            ListOfFilesBeforeRename, ListOfRenamedFiles
        ])
    ),
    ok.

%%%%%%%%%%%%%%% Utility Functions %%%%%%%%%%%%%%%
setupDb(Kind) ->
    ?assert(test_util:wait_healthy(), "Init service is not ready"),
    DbName = test_util:tempdb(),
    ShardsDbName = <<"shards/00000000-ffffffff/", DbName/binary, ".1730151232">>,
    configure(Kind),
    #context{
        dbname = DbName,
        shard_name = ShardsDbName
    }.

setup({Kind, Name}) ->
    Ctx0 = setupDb(Kind),
    Ctx1 = open_indexes(Ctx0),
    ok = update_index(1, 1, ?FIELDS, Ctx1),
    ok = update_index(2, 2, ?FIELDS, Ctx1),
    EchoPid = start_service(Name),
    Ctx1#context{echo_pid = EchoPid}.

open_indexes(#context{shard_name = ShardsDbName} = Ctx) ->
    Ctx#context{
        indexes = #{
            1 => open_index(1, ShardsDbName),
            2 => open_index(2, ShardsDbName)
        }
    }.

open_index(Idx, ShardsDbName) ->
    Sig = mock_sig({Idx, ShardsDbName}),
    Path = <<ShardsDbName/binary, "/", Sig/binary>>,
    Self = self(),
    ReqId = make_ref(),
    % use auxilary process to handle EXIT messages
    spawn(fun() ->
        {ok, IndexPid} = clouseau_rpc:open_index(self(), Path, ?Analyzer),
        Self ! {ReqId, IndexPid},
        receive
            {'EXIT', Pid, Reason} ->
                Self ! {'EXIT', Pid, Reason}
        end
    end),
    receive
        {ReqId, IndexPid} ->
            ?assert(is_pid(IndexPid)),
            #index{idx = Idx, path = Path, signature = binary_to_list(Sig), pid = IndexPid}
    end.

update_index(Idx, UpdateSeq, Fields, #context{indexes = Indexes}) ->
    #{Idx := #index{pid = IndexPid}} = Indexes,
    IdxBin = integer_to_binary(Idx),
    DocId = <<"doc", IdxBin/binary>>,
    ok = clouseau_rpc:update(IndexPid, DocId, Fields),
    ok = clouseau_rpc:commit(IndexPid, UpdateSeq),
    ok.

cleanup_index(Idx, #context{dbname = DbName, indexes = Indexes} = Ctx) ->
    ActiveSigs = [list_to_binary(I#index.signature) || {K, I} <- maps:to_list(Indexes), K =/= Idx],
    ?assertEqual(ok, clouseau_rpc:cleanup(DbName, ActiveSigs)),
    util:wait(
        fun() ->
            case get_files_grouped_by_index(Ctx) of
                {ok, {_, Map}} when map_size(Map) > 1 ->
                    wait;
                {ok, Result} ->
                    {ok, Result}
            end
        end,
        ?TIMEOUT_IN_MS
    ).

get_index(Idx, #context{indexes = Indexes}) ->
    #{Idx := #index{} = Index} = Indexes,
    Index.

mock_sig(Term) ->
    binary:encode_hex(erlang:md5(term_to_binary(Term)), lowercase).

% Returns given list of files grouped by folder
-spec group_by_index(Files :: [binary()]) -> #{Sig :: string() => [FileName :: string()]}.
group_by_index(Files) ->
    {ok, RegExp} = re:compile(<<".*\\/shards\\/[0-9a-f]+[-][0-9a-f]+\\/([^/]+)\\/([^/]+)\\/(.*)">>),
    {Folder, Map} = lists:foldl(
        fun(BinPath, {Prefix, Acc}) ->
            case re:run(BinPath, RegExp, [{capture, all, list}]) of
                {match, [_, Db, Sig, File]} ->
                    {Db, maps:put(Sig, [File | maps:get(Sig, Acc, [])], Acc)};
                nomatch ->
                    {Prefix, Acc}
            end
        end,
        {nil, #{}},
        Files
    ),
    {Folder, Map}.

start_service(Name) ->
    {ok, Pid} = gen_server:call({?INIT_SERVICE, ?NodeZ}, {spawn, echo, Name}),
    ?assert(is_pid(Pid)),
    Pid.

stop_service(_, Pid) ->
    exit(Pid, normal).

teardown(Ctx, #context{echo_pid = EchoPid, indexes = Indexes}) ->
    maps:map(
        fun(_, #index{pid = IndexPid}) ->
            unlink(IndexPid),
            stop_service(Ctx, IndexPid)
        end,
        Indexes
    ),
    stop_service(Ctx, EchoPid),
    ok;
teardown(_, _) ->
    ok.

configure(ioq) ->
    config:set(alias_enabled, true);
configure(gen_server) ->
    config:set(alias_enabled, false).

list_files(EchoService, ShardsDbName) ->
    gen_server:call(EchoService, {listFiles, ShardsDbName}).

list_renamed(EchoService, ShardsDbName) ->
    gen_server:call(EchoService, {listRenamed, ShardsDbName}).

wait_folder_deletion(EchoService, ShardsDbName) ->
    util:wait(
        fun() ->
            case list_files(EchoService, ShardsDbName) of
                {ok, _} ->
                    wait;
                {error, {notDirectoryException, Path, _}} ->
                    {ok, Path};
                {error, {noSuchFileException, Path, _}} ->
                    {ok, Path}
            end
        end,
        ?TIMEOUT_IN_MS
    ).

get_files_grouped_by_index(#context{echo_pid = EchoService, shard_name = ShardsDbName}) ->
    maybe
        {_, {ok, ListOfFiles}} ?= {list_files, list_files(EchoService, ShardsDbName)},
        {_, {_, #{}} = Result} ?= {group_by_index, (catch group_by_index(ListOfFiles))},
        {ok, Result}
    end.

get_renamed_files_grouped_by_index(#context{echo_pid = EchoService, shard_name = ShardsDbName}) ->
    maybe
        {_, {ok, ListOfFiles}} ?= {list_files, list_renamed(EchoService, ShardsDbName)},
        {_, {_, #{}} = Result} ?= {group_by_index, (catch group_by_index(ListOfFiles))},
        {ok, Result}
    end.
