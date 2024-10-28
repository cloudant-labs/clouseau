-module(clouseau_rpc).
-export([open_index/3]).
-export([await/2, commit/2, get_update_seq/1, info/1, search/2]).
-export([group1/7, group2/2]).
-export([delete/2, update/3, cleanup/1, cleanup/2, rename/1]).
-export([analyze/2, version/0, disk_size/1]).
-export([set_purge_seq/2, get_purge_seq/1, get_root_dir/0]).

-include("zeunit.hrl").

open_index(Peer, Path, Analyzer) ->
    rpc({main, clouseau()}, {open, Peer, Path, Analyzer}).

disk_size(Path) ->
    rpc({main, clouseau()}, {disk_size, Path}).

get_root_dir() ->
    rpc({main, clouseau()}, {get_root_dir}).

await(Ref, MinSeq) ->
    rpc(Ref, {await, MinSeq}).

commit(Ref, NewCommitSeq) ->
    rpc(Ref, {commit, NewCommitSeq}).

info(Ref) ->
    rpc(Ref, info).

get_update_seq(Ref) ->
    rpc(Ref, get_update_seq).

set_purge_seq(Ref, Seq) ->
    rpc(Ref, {set_purge_seq, Seq}).

get_purge_seq(Ref) ->
    rpc(Ref, get_purge_seq).

search(Ref, Args) ->
    case rpc(Ref, {search, Args}) of
        {ok, Response} when is_list(Response) ->
            {ok, #top_docs{
                update_seq = util:get_value(update_seq, Response),
                total_hits = util:get_value(total_hits, Response),
                hits = util:get_value(hits, Response),
                counts = util:get_value(counts, Response),
                ranges = util:get_value(ranges, Response)
            }};
        Else ->
            Else
    end.

group1(Ref, Query, GroupBy, Refresh, Sort, Offset, Limit) ->
    rpc(Ref, {group1, Query, GroupBy, Refresh, Sort, Offset, Limit}).

group2(Ref, Args) ->
    rpc(Ref, {group2, Args}).

delete(Ref, Id) ->
    rpc(Ref, {delete, util:to_binary(Id)}).

update(Ref, Id, Fields) ->
    rpc(Ref, {update, Id, Fields}).

cleanup(DbName) ->
    gen_server:cast({cleanup, clouseau()}, {cleanup, DbName}).

rename(DbName) ->
    gen_server:cast({cleanup, clouseau()}, {rename, DbName}).

cleanup(DbName, ActiveSigs) ->
    gen_server:cast({cleanup, clouseau()}, {cleanup, DbName, ActiveSigs}).

analyze(Analyzer, Text) ->
    rpc({analyzer, clouseau()}, {analyze, Analyzer, Text}).

version() ->
    rpc({main, clouseau()}, version).

clouseau() ->
    ?NodeZ.

rpc(Ref, Msg) ->
    util:call(Ref, Msg).
