----------------------------- MODULE Clouseau -----------------------------
(***************************************************************************)
(* Formal TLA+ specification of Clouseau.                                  *)
(*                                                                         *)
(* Key architectural flaws in upstream master:                             *)
(* 1. LRU cache returns stale / terminating / dead PIDs upon reopen        *)
(*    because removal only happens asynchronously on trapMonitorExit.      *)
(* 2. Concurrent open waiters only link the first opener peer; subsequent  *)
(*    waiters in the queue are NOT linked to the newly spawned actor.      *)
(* 3. Non-atomic directory deletion allows concurrent opens to read half-  *)
(*    deleted Lucene index directories.                                    *)
(* 4. Corrupted index directories (FileNotFound / EOF) crash without      *)
(*    auto-recovery, locking out subsequent open attempts.                 *)
(***************************************************************************)

EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    Paths,          \* Set of index paths, e.g. {"idx1", "idx2"}
    DocIds,         \* Set of Document IDs, e.g. {"docA", "docB"}
    MaxPids,        \* Finite set of actor PIDs, e.g. {"pid1", "pid2"}
    MaxWaiters,     \* Set of concurrent Dreyfus waiter clients, e.g. {"w1", "w2"}
    LRUCapacity     \* Max LRU size, e.g. 1

VARIABLES
    lruMap,             \* [path -> Pid \cup {"none"}]
    lruOrder,           \* Sequence of paths in LRU order
    waiters,            \* [path -> Set of waiter IDs]
    waiterStatus,       \* [w -> "idle" | "waiting" | "ok" | "error"]
    waiterReceivedPid,  \* [w -> Pid \cup {"none"}]

    pidState,           \* [p -> "unallocated" | "opening" | "running" | "terminating" | "dead"]
    pidPath,            \* [p -> path \cup {"none"}]
    clouseauLinks,      \* [p -> Set of Paths] Links held on Clouseau actor side

    dreyfusIndexState,  \* [path -> "idle" | "opening" | "linked_open"]
    dreyfusIndexPid,    \* [path -> Pid \cup {"none"}]
    erlangLinks,        \* [path -> Set of Pids] Links held on Dreyfus Erlang side

    fsDirState,         \* [path -> "nonexistent" | "exists" | "deleting"]
    fsLockHeld,         \* [path -> BOOLEAN]
    fsCorrupted,        \* [path -> BOOLEAN]
    luceneDocs,         \* [p -> [doc -> [rev: Int, status: "active"|"deleted"|"purged"]]]
    pendingSeq,         \* [p -> Int]
    committedSeq,       \* [p -> Int]
    purgeSeq,           \* [p -> Int]
    pendingPurgeSeq,    \* [p -> Int]
    committing          \* [p -> BOOLEAN]

vars == << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
           pidState, pidPath, clouseauLinks,
           dreyfusIndexState, dreyfusIndexPid, erlangLinks,
           fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
           pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

-----------------------------------------------------------------------------
(* Initial State *)

Init ==
    /\ lruMap = [path \in Paths |-> "none"]
    /\ lruOrder = <<>>
    /\ waiters = [path \in Paths |-> {}]
    /\ waiterStatus = [w \in MaxWaiters |-> "idle"]
    /\ waiterReceivedPid = [w \in MaxWaiters |-> "none"]

    /\ pidState = [p \in MaxPids |-> "unallocated"]
    /\ pidPath = [p \in MaxPids |-> "none"]
    /\ clouseauLinks = [p \in MaxPids |-> {}]

    /\ dreyfusIndexState = [path \in Paths |-> "idle"]
    /\ dreyfusIndexPid = [path \in Paths |-> "none"]
    /\ erlangLinks = [path \in Paths |-> {}]

    /\ fsDirState = [path \in Paths |-> "exists"]
    /\ fsLockHeld = [path \in Paths |-> FALSE]
    /\ fsCorrupted = [path \in Paths |-> FALSE]
    /\ luceneDocs = [p \in MaxPids |-> [d \in DocIds |-> [rev |-> 0, status |-> "purged"]]]
    /\ pendingSeq = [p \in MaxPids |-> 0]
    /\ committedSeq = [p \in MaxPids |-> 0]
    /\ purgeSeq = [p \in MaxPids |-> 0]
    /\ pendingPurgeSeq = [p \in MaxPids |-> 0]
    /\ committing = [p \in MaxPids |-> FALSE]

-----------------------------------------------------------------------------
(* Helper Operators *)

RemoveFromSeq(s, elem) ==
    SelectSeq(s, LAMBDA x: x /= elem)

AppendLRU(s, elem) ==
    Append(RemoveFromSeq(s, elem), elem)

-----------------------------------------------------------------------------
(* Actions: Clean Upstream Master Implementation *)

(* Bug 1 in master: Dreyfus open on LRU hit blindly returns lruMap[path] *)
(* without checking if the process is alive or terminating, and does not link peer *)
DreyfusOpenCacheHit(w, path) ==
    /\ waiterStatus[w] = "idle"
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "ok"]
       /\ waiterReceivedPid' = [waiterReceivedPid EXCEPT ![w] = p]
       /\ dreyfusIndexState' = [dreyfusIndexState EXCEPT ![path] = "linked_open"]
       /\ dreyfusIndexPid' = [dreyfusIndexPid EXCEPT ![path] = p]
       /\ erlangLinks' = [erlangLinks EXCEPT ![path] = erlangLinks[path] \cup {p}]
       \* Upstream bug: Clouseau does NOT call node.link(peer, cachedPid) on cache hit!
       /\ UNCHANGED << clouseauLinks, lruMap, lruOrder, waiters, pidState, pidPath,
                       fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Opener requests index when not in LRU: queues waiter and spawns actor *)
DreyfusOpenStartOpener(w, path) ==
    /\ waiterStatus[w] = "idle"
    /\ lruMap[path] = "none"
    /\ waiters[path] = {}
    /\ \E p \in MaxPids :
        /\ pidState[p] = "unallocated"
        /\ pidState' = [pidState EXCEPT ![p] = "opening"]
        /\ pidPath' = [pidPath EXCEPT ![p] = path]
        /\ waiters' = [waiters EXCEPT ![path] = {w}]
        /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "waiting"]
        /\ UNCHANGED << lruMap, lruOrder, waiterReceivedPid, clouseauLinks,
                        dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                        fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                        pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Subsequent concurrent callers queue in waiters *)
DreyfusOpenQueueWaiter(w, path) ==
    /\ waiterStatus[w] = "idle"
    /\ lruMap[path] = "none"
    /\ waiters[path] /= {}
    /\ waiters' = [waiters EXCEPT ![path] = waiters[path] \cup {w}]
    /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "waiting"]
    /\ UNCHANGED << lruMap, lruOrder, waiterReceivedPid, pidState, pidPath, clouseauLinks,
                    dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                    fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Actor successfully starts *)
(* Bug 2 in master: node.link is ONLY called for the first spawner peer! *)
(* Other waiters in waiters[path] receive ('ok, pid) but are NEVER linked! *)
IndexServiceStartOk(p) ==
    /\ pidState[p] = "opening"
    /\ LET path == pidPath[p] IN
       /\ fsLockHeld[path] = FALSE
       /\ fsCorrupted[path] = FALSE
       /\ fsDirState[path] = "exists"
       /\ pidState' = [pidState EXCEPT ![p] = "running"]
       /\ fsLockHeld' = [fsLockHeld EXCEPT ![path] = TRUE]
       /\ lruMap' = [lruMap EXCEPT ![path] = p]
       /\ lruOrder' = AppendLRU(lruOrder, path)
       /\ waiterStatus' = [w \in MaxWaiters |->
            IF w \in waiters[path] THEN "ok" ELSE waiterStatus[w]]
       /\ waiterReceivedPid' = [w \in MaxWaiters |->
            IF w \in waiters[path] THEN p ELSE waiterReceivedPid[w]]
       /\ dreyfusIndexState' = [dreyfusIndexState EXCEPT ![path] = "linked_open"]
       /\ dreyfusIndexPid' = [dreyfusIndexPid EXCEPT ![path] = p]
       /\ erlangLinks' = [erlangLinks EXCEPT ![path] = erlangLinks[path] \cup {p}]
       \* Upstream bug: Clouseau only links ONE path/peer on handleInfo open_ok
       /\ clouseauLinks' = [clouseauLinks EXCEPT ![p] = {path}]
       /\ waiters' = [waiters EXCEPT ![path] = {}]
       /\ UNCHANGED << pidPath, fsDirState, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Actor crashes on corrupted index directory *)
(* Bug 4 in master: Upstream crashes with error and does NOT recover/rebuild *)
IndexServiceStartFailCorrupted(p) ==
    /\ pidState[p] = "opening"
    /\ LET path == pidPath[p] IN
       /\ (fsCorrupted[path] = TRUE \/ fsDirState[path] = "deleting")
       /\ pidState' = [pidState EXCEPT ![p] = "dead"]
       /\ waiterStatus' = [w \in MaxWaiters |->
            IF w \in waiters[path] THEN "error" ELSE waiterStatus[w]]
       /\ waiters' = [waiters EXCEPT ![path] = {}]
       /\ UNCHANGED << lruMap, lruOrder, waiterReceivedPid, pidPath, clouseauLinks,
                       dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                       fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Waiter consumes reply *)
WaiterAckReply(w) ==
    /\ waiterStatus[w] \in {"ok", "error"}
    /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "idle"]
    /\ waiterReceivedPid' = [waiterReceivedPid EXCEPT ![w] = "none"]
    /\ UNCHANGED << lruMap, lruOrder, waiters, pidState, pidPath, clouseauLinks,
                    dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                    fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Index operations *)
DreyfusDocUpdate(p, docId, rev) ==
    /\ pidState[p] = "running"
    /\ rev > luceneDocs[p][docId].rev
    /\ luceneDocs' = [luceneDocs EXCEPT ![p][docId] = [rev |-> rev, status |-> "active"]]
    /\ pendingSeq' = [pendingSeq EXCEPT ![p] = pendingSeq[p] + 1]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsDirState, fsLockHeld, fsCorrupted,
                    committedSeq, purgeSeq, pendingPurgeSeq, committing >>

DreyfusDocDelete(p, docId, rev) ==
    /\ pidState[p] = "running"
    /\ rev > luceneDocs[p][docId].rev
    /\ luceneDocs' = [luceneDocs EXCEPT ![p][docId] = [rev |-> rev, status |-> "deleted"]]
    /\ pendingSeq' = [pendingSeq EXCEPT ![p] = pendingSeq[p] + 1]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsDirState, fsLockHeld, fsCorrupted,
                    committedSeq, purgeSeq, pendingPurgeSeq, committing >>

CommitStart(p) ==
    /\ pidState[p] = "running"
    /\ ~committing[p]
    /\ (pendingSeq[p] > committedSeq[p] \/ pendingPurgeSeq[p] > purgeSeq[p])
    /\ committing' = [committing EXCEPT ![p] = TRUE]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq >>

CommitSuccess(p) ==
    /\ pidState[p] = "running"
    /\ committing[p]
    /\ committing' = [committing EXCEPT ![p] = FALSE]
    /\ committedSeq' = [committedSeq EXCEPT ![p] = pendingSeq[p]]
    /\ purgeSeq' = [purgeSeq EXCEPT ![p] = pendingPurgeSeq[p]]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, pendingPurgeSeq >>

(* LRU eviction in upstream master: marks actor for termination but LEAVES entry in lruMap *)
(* until trapMonitorExit executes later *)
ManagerLRUEvict ==
    /\ Len(lruOrder) > LRUCapacity
    /\ LET evictPath == Head(lruOrder)
           p == lruMap[evictPath] IN
       /\ p /= "none"
       /\ pidState[p] = "running"
       /\ pidState' = [pidState EXCEPT ![p] = "terminating"]
       /\ lruOrder' = Tail(lruOrder)
       \* Upstream bug: lruMap[evictPath] is NOT cleared here!
       /\ UNCHANGED << lruMap, waiters, waiterStatus, waiterReceivedPid, pidPath,
                       clouseauLinks, dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                       fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Process completes termination *)
IndexServiceDies(p) ==
    /\ pidState[p] \in {"terminating", "opening", "running"}
    /\ LET path == pidPath[p] IN
       /\ pidState' = [pidState EXCEPT ![p] = "dead"]
       /\ fsLockHeld' = [fsLockHeld EXCEPT ![path] = FALSE]
       /\ clouseauLinks' = [clouseauLinks EXCEPT ![p] = {}]
       /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                       pidPath, dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                       fsDirState, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Upstream master only clears lruMap on trapMonitorExit *)
ManagerTrapMonitorExit(path) ==
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ pidState[p] = "dead"
       /\ lruMap' = [lruMap EXCEPT ![path] = "none"]
       /\ UNCHANGED << lruOrder, waiters, waiterStatus, waiterReceivedPid, pidState,
                       pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                       erlangLinks, fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* Dreyfus client handles exit of dead process *)
DreyfusTrapExit(path) ==
    /\ dreyfusIndexState[path] = "linked_open"
    /\ LET p == dreyfusIndexPid[path] IN
       /\ pidState[p] = "dead"
       /\ dreyfusIndexState' = [dreyfusIndexState EXCEPT ![path] = "idle"]
       /\ dreyfusIndexPid' = [dreyfusIndexPid EXCEPT ![path] = "none"]
       /\ erlangLinks' = [erlangLinks EXCEPT ![path] = erlangLinks[path] \ {p}]
       /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                       pidState, pidPath, clouseauLinks, fsDirState, fsLockHeld,
                       fsCorrupted, luceneDocs, pendingSeq, committedSeq, purgeSeq,
                       pendingPurgeSeq, committing >>

(* Bug 3 in master: Non-atomic recursive cleanup *)
(* Moves directory to "deleting" state in-place without tombstoning *)
CleanupDeleteNonAtomicStart(path) ==
    /\ fsDirState[path] = "exists"
    /\ fsLockHeld[path] = FALSE
    /\ fsDirState' = [fsDirState EXCEPT ![path] = "deleting"]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

CleanupDeleteNonAtomicDone(path) ==
    /\ fsDirState[path] = "deleting"
    /\ fsDirState' = [fsDirState EXCEPT ![path] = "exists"]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

DiskCorrupted(path) ==
    /\ fsDirState[path] = "exists"
    /\ ~fsCorrupted[path]
    /\ fsCorrupted' = [fsCorrupted EXCEPT ![path] = TRUE]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsDirState, fsLockHeld, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

-----------------------------------------------------------------------------
(* Next State Relation *)

Next ==
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenCacheHit(w, path)
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenStartOpener(w, path)
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenQueueWaiter(w, path)
    \/ \E p \in MaxPids : IndexServiceStartOk(p)
    \/ \E p \in MaxPids : IndexServiceStartFailCorrupted(p)
    \/ \E w \in MaxWaiters : WaiterAckReply(w)
    \/ \E p \in MaxPids, d \in DocIds, rev \in {1, 2} : DreyfusDocUpdate(p, d, rev)
    \/ \E p \in MaxPids, d \in DocIds, rev \in {1, 2} : DreyfusDocDelete(p, d, rev)
    \/ \E p \in MaxPids : CommitStart(p)
    \/ \E p \in MaxPids : CommitSuccess(p)
    \/ ManagerLRUEvict
    \/ \E p \in MaxPids : IndexServiceDies(p)
    \/ \E path \in Paths : ManagerTrapMonitorExit(path)
    \/ \E path \in Paths : DreyfusTrapExit(path)
    \/ \E path \in Paths : CleanupDeleteNonAtomicStart(path)
    \/ \E path \in Paths : CleanupDeleteNonAtomicDone(path)
    \/ \E path \in Paths : DiskCorrupted(path)

Spec == Init /\ [][Next]_vars

-----------------------------------------------------------------------------
(* INVARIANTS *)

(* Invariant 1: No Stale PID Returned to Reopening Dreyfus Index *)
NoStalePidOnReopen ==
    \A path \in Paths :
        (dreyfusIndexState[path] = "linked_open" /\ lruMap[path] /= "none") =>
            LET p == dreyfusIndexPid[path] IN
            /\ p \in MaxPids
            /\ pidPath[p] = path
            /\ pidState[p] \in {"opening", "running"}

(* Invariant 2: JInterface Link Symmetry *)
JInterfaceLinkSymmetry ==
    \A path \in Paths, p \in MaxPids :
        (p \in erlangLinks[path] <=> path \in clouseauLinks[p])

(* Invariant 3: Active Index Directories Must Be Valid *)
ActiveIndexValidDir ==
    \A path \in Paths :
        (lruMap[path] \in MaxPids) =>
            LET p == lruMap[path] IN
            (pidState[p] = "running") => (fsDirState[path] = "exists")

=============================================================================
