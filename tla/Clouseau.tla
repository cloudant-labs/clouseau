----------------------------- MODULE Clouseau --------------------------------
(*****************************************************************************)
(* Formal TLA+ specification of Clouseau.                                    *)
(*                                                                           *)
(* WHAT IS THIS FILE?                                                        *)
(* This is a mathematical state-machine model of Clouseau's distributed      *)
(* Lucene indexing service and its Erlang/OTP client (Dreyfus in CouchDB).   *)
(* Instead of testing a few hand-crafted timing scenarios, Leslie            *)
(* Lamport's TLC model checker uses this specification to systematically     *)
(* explore every mathematically possible ordering of concurrent events.      *)
(*                                                                           *)
(* For an introduction to TLA+, see Leslie Lamport's tutorial:               *)
(* https://lamport.azurewebsites.net/tla/high-level-view.html                *)
(*                                                                           *)
(* HOW TO READ TLA+:                                                         *)
(* 1. VARIABLES hold the system state across steps (e.g. actor tables,       *)
(*    mailbox queues, link registries).                                      *)
(* 2. ACTIONS are formulas describing valid transitions from state Sₙ to     *)
(*    state Sₙ₊₁ (step n to step n + 1). An unprimed variable `x` represents *)
(*    its value BEFORE the step; a primed variable `x'` represents its       *)
(*    value AFTER the step.                                                  *)
(* 3. CONJUNCTIONS (`/\`) and DISJUNCTIONS (`\/`) are logical AND / OR.      *)
(*    In an action, `/\` serves as both precondition guards (must be true    *)
(*    to execute) and state assignment formulas.                             *)
(* 4. UNCHANGED <<...>> explicitly states that all other state variables     *)
(*    remain identical in this step (TLA+ requires explicit framing).        *)
(* 5. INVARIANTS are Boolean safety properties that must evaluate to TRUE    *)
(*    in every reachable state graph node. If any interleaving breaks an     *)
(*    invariant, TLC outputs a step-by-step counterexample trace.            *)
(*                                                                           *)
(* MAPPING TO CODEBASE:                                                      *)
(* - IndexManagerService.scala : LRU cache, open waiter queue, JInterface    *)
(* - IndexService.scala        : Lucene index actor lifecycle & commits      *)
(* - IndexCleanupService.scala : Directory cleanup & soft deletion           *)
(* - Dreyfus (CouchDB)         : Erlang/OTP peer client & process links      *)
(*****************************************************************************)

EXTENDS Integers, Sequences, FiniteSets, TLC

(* ========================================================================= *)
(* 1. MODEL CONSTANTS (Configuration Bounds)                                 *)
(* ========================================================================= *)
(* These constants define the finite model dimensions supplied by .cfg files.*)
(* Keeping sets small (e.g. 2 paths, 2 PIDs, 2 waiters) allows TLC to        *)
(* thoroughly explore the state space without combinatorial explosion.       *)

CONSTANTS
    Paths,          \* Set of index paths, e.g. {"idx1", "idx2"}
    DocIds,         \* Set of Document IDs, e.g. {"docA", "docB"}
    MaxPids,        \* Finite set of actor PIDs, e.g. {"pid1", "pid2"}
    MaxWaiters,     \* Set of concurrent Dreyfus client callers, e.g. {"w1", "w2"}
    LRUCapacity     \* Maximum number of actively cached indices in LRU, e.g. 1

(* ========================================================================= *)
(* 2. STATE VARIABLES                                                        *)
(* ========================================================================= *)

VARIABLES
    (* --- IndexManagerService LRU Cache & Waiter Queue --- *)
    lruMap,             \* Function [path → Pid ∪ {"none"}]: active cache mapping
    lruOrder,           \* Sequence of paths in least-recently-used order
    waiters,            \* Function [path → Set of waiter IDs]: callers queued during open
    waiterStatus,       \* Function [w → "idle" | "waiting" | "ok" | "error"]: client status
    waiterReceivedPid,  \* Function [w → Pid ∪ {"none"}]: PID returned to client

    (* --- Clouseau Actor Process Lifecycle --- *)
    pidState,           \* Function [p → "unallocated" | "opening" | "running" | "terminating" | "dead"]
    pidPath,            \* Function [p → path ∪ {"none"}]: path managed by PID p
    clouseauLinks,      \* Function [p → Set of Paths]: links held on Java/Clouseau side

    (* --- Distributed Erlang/OTP / Dreyfus Peer State (CouchDB) --- *)
    dreyfusIndexState,  \* Function [path → "idle" | "opening" | "linked_open"]: caller view
    dreyfusIndexPid,    \* Function [path → Pid ∪ {"none"}]: cached PID held by Dreyfus
    erlangLinks,        \* Function [path → Set of Pids]: links held on Erlang side

    (* --- On-Disk Lucene Storage & Lock State --- *)
    fsDirState,         \* Function [path → "nonexistent" | "exists" | "deleting"]
    fsLockHeld,         \* Function [path → BOOLEAN]: NativeFSLock held status
    fsCorrupted,        \* Function [path → BOOLEAN]: file corruption / truncation status
    luceneDocs,         \* Function [p → [doc → [rev: Int, status: "active"|"deleted"|"purged"]]]
    pendingSeq,         \* Function [p → Int]: in-memory document update counter
    committedSeq,       \* Function [p → Int]: on-disk committed sequence number
    purgeSeq,           \* Function [p → Int]: on-disk purge sequence number
    pendingPurgeSeq,    \* Function [p → Int]: in-memory purge counter
    committing          \* Function [p → BOOLEAN]: in-flight Lucene fsync / commit

(* Tuple of all state variables used for stuttering transitions *)
vars == << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
           pidState, pidPath, clouseauLinks,
           dreyfusIndexState, dreyfusIndexPid, erlangLinks,
           fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
           pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ========================================================================= *)
(* 3. INITIAL STATE DEFINITION                                               *)
(* ========================================================================= *)
(* Init specifies the initial state of every variable at system startup.     *)

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

(* ========================================================================= *)
(* 4. HELPER OPERATORS                                                       *)
(* ========================================================================= *)

(* Pure sequence manipulation helpers for LRU cache tracking *)
RemoveFromSeq(s, elem) ==
    SelectSeq(s, LAMBDA x: x /= elem)

AppendLRU(s, elem) ==
    Append(RemoveFromSeq(s, elem), elem)

(* ========================================================================= *)
(* 5. ACTION TRANSITIONS                                                     *)
(* ========================================================================= *)

(* ------------------------------------------------------------------------- *)
(* Action: DreyfusOpenCacheHit                                               *)
(* Scala: IndexManagerService.handleCall(OpenIndexMsg(peer, path, ...))      *)
(*                                                                           *)
(* When a client requests an index that is already in lruMap:                *)
(* - Checks isProcessAlive(cachedPid) == true (pidState = "running")         *)
(* - Links the calling peer (node.link(peer, cachedPid))                     *)
(* - Returns ('ok, cachedPid) immediately without opening from disk          *)
(* ------------------------------------------------------------------------- *)
DreyfusOpenCacheHit(w, path) ==
    /\ waiterStatus[w] = "idle"
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ pidState[p] = "running"
       /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "ok"]
       /\ waiterReceivedPid' = [waiterReceivedPid EXCEPT ![w] = p]
       /\ dreyfusIndexState' = [dreyfusIndexState EXCEPT ![path] = "linked_open"]
       /\ dreyfusIndexPid' = [dreyfusIndexPid EXCEPT ![path] = p]
       /\ erlangLinks' = [erlangLinks EXCEPT ![path] = erlangLinks[path] \cup {p}]
       /\ clouseauLinks' = [clouseauLinks EXCEPT ![p] = clouseauLinks[p] \cup {path}]
       /\ UNCHANGED << lruMap, lruOrder, waiters, pidState, pidPath,
                       fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: DreyfusOpenCacheEvictDead                                         *)
(* Scala: IndexManagerService.handleCall (cachedPid != null && !isAlive)     *)
(*                                                                           *)
(* If an LRU entry exists but is dead or terminating, eagerly                *)
(* remove it from lruMap rather than returning it to the caller.             *)
(* ------------------------------------------------------------------------- *)
DreyfusOpenCacheEvictDead(path) ==
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ pidState[p] \in {"dead", "terminating"}
       /\ lruMap' = [lruMap EXCEPT ![path] = "none"]
       /\ UNCHANGED << lruOrder, waiters, waiterStatus, waiterReceivedPid,
                       pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                       erlangLinks, fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: DreyfusOpenStartOpener                                            *)
(* Scala: IndexManagerService.handleCall (cache miss -> spawn background)    *)
(*                                                                           *)
(* When an index is not in LRU and no worker is in flight:                   *)
(* - Allocates an available PID in state "opening"                           *)
(* - Queues caller w in waiters[path]                                        *)
(* - Spawns a background worker fiber (IndexService.start)                   *)
(* - Returns 'noreply' to release the manager actor mailbox immediately      *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Action: DreyfusOpenQueueWaiter                                            *)
(* Scala: IndexManagerService.handleCall (waiters.get(path) is Some(list))   *)
(*                                                                           *)
(* Subsequent concurrent open calls for the same path queue in waiters.      *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Action: IndexServiceStartOk                                               *)
(* Scala: IndexManagerService.handleInfo(('open_ok, path, pid))              *)
(*                                                                           *)
(* Background worker fiber finishes disk opening and notifies manager:       *)
(* - Places PID into lruMap and updates LRU eviction order                   *)
(* - Iterates waiters[path] and links ALL queued callers                     *)
(* - Replies ('ok, pid) to all waiting clients                               *)
(* ------------------------------------------------------------------------- *)
IndexServiceStartOk(p) ==
    /\ pidState[p] = "opening"
    /\ LET path == pidPath[p] IN
       /\ fsLockHeld[path] = FALSE
       /\ fsCorrupted[path] = FALSE
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
       /\ clouseauLinks' = [clouseauLinks EXCEPT ![p] = {path}]
       /\ waiters' = [waiters EXCEPT ![path] = {}]
       /\ UNCHANGED << pidPath, fsDirState, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: IndexServiceStartFailCorrupted [ACTUAL BEHAVIOR ON MAIN]          *)
(* Scala: IndexManagerService.handleInfo(('open_error, path, error))         *)
(*                                                                           *)
(* Status: Models current behavior where disk corruption or missing segment  *)
(* files causes the worker process to crash without self-healing.            *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Action: WaiterAckReply                                                    *)
(* Client in Dreyfus consumes the mailbox reply and resets to idle           *)
(* ------------------------------------------------------------------------- *)
WaiterAckReply(w) ==
    /\ waiterStatus[w] \in {"ok", "error"}
    /\ waiterStatus' = [waiterStatus EXCEPT ![w] = "idle"]
    /\ waiterReceivedPid' = [waiterReceivedPid EXCEPT ![w] = "none"]
    /\ UNCHANGED << lruMap, lruOrder, waiters, pidState, pidPath, clouseauLinks,
                    dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                    fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: ManagerDeleteNotFound [ACTUAL BEHAVIOR ON MAIN - BUG]             *)
(* Scala: IndexManagerService.handleCall(DeleteDocMsg(path))                 *)
(*                                                                           *)
(* Status: Mailbox receives ('delete, path) while opener is in-flight.       *)
(* Manager returns not_found, triggering direct cleanup disk deletion.       *)
(* ------------------------------------------------------------------------- *)
ManagerDeleteNotFound(path) ==
    /\ lruMap[path] = "none"
    /\ fsDirState[path] = "exists"
    /\ fsLockHeld[path] = FALSE
    /\ fsDirState' = [fsDirState EXCEPT ![path] = "nonexistent"]
    /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                    pidState, pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                    erlangLinks, fsLockHeld, fsCorrupted, luceneDocs,
                    pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: ManagerDeleteActive                                               *)
(* Scala: IndexManagerService.handleCall(DeleteDocMsg(path))                 *)
(*                                                                           *)
(* When index is actively running in LRU, sends 'delete message to actor     *)
(* ------------------------------------------------------------------------- *)
ManagerDeleteActive(path) ==
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ pidState[p] = "running"
       /\ pidState' = [pidState EXCEPT ![p] = "terminating"]
       /\ fsDirState' = [fsDirState EXCEPT ![path] = "nonexistent"]
       /\ UNCHANGED << lruMap, lruOrder, waiters, waiterStatus, waiterReceivedPid,
                       pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                       erlangLinks, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Actions: Lucene Document Updates and Commits                              *)
(* Scala: IndexService.scala document updates, deletes, and fsync commits    *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Action: ManagerLRUEvict                                                   *)
(* Scala: IndexManagerService.LRU.enforceCapacity                            *)
(* When cache size exceeds LRUCapacity, sends ('close, 'lru) to oldest actor *)
(* ------------------------------------------------------------------------- *)
ManagerLRUEvict ==
    /\ Len(lruOrder) > LRUCapacity
    /\ LET evictPath == Head(lruOrder)
           p == lruMap[evictPath] IN
       /\ p /= "none"
       /\ pidState[p] = "running"
       /\ pidState' = [pidState EXCEPT ![p] = "terminating"]
       /\ lruOrder' = Tail(lruOrder)
       /\ UNCHANGED << lruMap, waiters, waiterStatus, waiterReceivedPid, pidPath,
                       clouseauLinks, dreyfusIndexState, dreyfusIndexPid, erlangLinks,
                       fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: IndexServiceDies                                                  *)
(* Process terminates / crashes on JVM side                                  *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Action: ManagerTrapMonitorExit                                            *)
(* Scala: IndexManagerService.trapMonitorExit(pid, ref, reason)              *)
(* Asynchronous process monitor exit message arrives from JInterface channel *)
(* ------------------------------------------------------------------------- *)
ManagerTrapMonitorExit(path) ==
    /\ lruMap[path] /= "none"
    /\ LET p == lruMap[path] IN
       /\ pidState[p] = "dead"
       /\ lruMap' = [lruMap EXCEPT ![path] = "none"]
       /\ UNCHANGED << lruOrder, waiters, waiterStatus, waiterReceivedPid, pidState,
                       pidPath, clouseauLinks, dreyfusIndexState, dreyfusIndexPid,
                       erlangLinks, fsDirState, fsLockHeld, fsCorrupted, luceneDocs,
                       pendingSeq, committedSeq, purgeSeq, pendingPurgeSeq, committing >>

(* ------------------------------------------------------------------------- *)
(* Action: DreyfusTrapExit                                                   *)
(* Erlang client process receives asynchronous 'EXIT' signal across network  *)
(* ------------------------------------------------------------------------- *)
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

(* ------------------------------------------------------------------------- *)
(* Actions: Index Cleanup Service [ACTUAL BEHAVIOR ON MAIN - BUG]            *)
(* Scala: IndexCleanupService.scala non-atomic in-place directory deletion   *)
(* Status: Models non-atomic deletion where partial dirs are exposed.        *)
(* ------------------------------------------------------------------------- *)
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

(* ========================================================================= *)
(* 6. NEXT-STATE RELATION & TEMPORAL SPECIFICATION                           *)
(* ========================================================================= *)
(* Next is the disjunction (\/) of all valid atomic state transitions.       *)
(* TLC evaluates Next from current state to generate all next possible states.*)

Next ==
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenCacheHit(w, path)
    \/ \E path \in Paths : DreyfusOpenCacheEvictDead(path)
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenStartOpener(w, path)
    \/ \E w \in MaxWaiters, path \in Paths : DreyfusOpenQueueWaiter(w, path)
    \/ \E p \in MaxPids : IndexServiceStartOk(p)
    \/ \E p \in MaxPids : IndexServiceStartFailCorrupted(p)
    \/ \E w \in MaxWaiters : WaiterAckReply(w)
    \/ \E path \in Paths : ManagerDeleteNotFound(path)
    \/ \E path \in Paths : ManagerDeleteActive(path)
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

(* The complete temporal formula: starts in Init, followed by Next steps *)
Spec == Init /\ [][Next]_vars

(* ========================================================================= *)
(* 7. SAFETY INVARIANTS                                                      *)
(* ========================================================================= *)
(* Invariants are Boolean formulas that must hold true in EVERY state.       *)
(* If TLC finds ANY reachable state where an invariant is FALSE, it halts    *)
(* and outputs the exact counterexample trace of actions that caused it.     *)

(* ------------------------------------------------------------------------- *)
(* Invariant 1: No Stale PID Returned on Index Reopen                        *)
(* Guarantee: When Clouseau replies 'ok' to an open request, the returned    *)
(* process must NOT be in the "dead" state.                                  *)
(* ------------------------------------------------------------------------- *)
NoStalePidOnReopen ==
    \A w \in MaxWaiters :
        (waiterStatus[w] = "ok" /\ waiterReceivedPid[w] /= "none") =>
            pidState[waiterReceivedPid[w]] \in {"opening", "running"}

(* ------------------------------------------------------------------------- *)
(* Invariant 2: JInterface Link Symmetry                                     *)
(* Guarantee: Cross-node process links must match between Erlang and Java    *)
(* for all running processes (Erlang link/1 vs Java node.link).              *)
(* ------------------------------------------------------------------------- *)
JInterfaceLinkSymmetry ==
    \A path \in Paths, p \in MaxPids :
        (pidState[p] = "running") =>
            (p \in erlangLinks[path] <=> path \in clouseauLinks[p])

(* ------------------------------------------------------------------------- *)
(* Invariant 3: Active Index Directories Must Be Valid                       *)
(* Guarantee: An index actively held in the LRU cache must point to an       *)
(* existing directory on disk.                                               *)
(* ------------------------------------------------------------------------- *)
ActiveIndexValidDir ==
    \A path \in Paths :
        (lruMap[path] \in MaxPids) =>
            LET p == lruMap[path] IN
            (pidState[p] = "running") => (fsDirState[path] = "exists")

=============================================================================
