# Formal Verification of Clouseau with TLA+

This directory contains formal specifications and TLC model configurations for Clouseau's core concurrency, actor lifecycles, Lucene storage, directory cleanup, and Erlang OTP distribution protocols.

It serves as an executable architecture model and verification harness designed to be inspected, run, and evolved to diagnose distributed race conditions, evaluate PRs, and verify bug fixes.

---

## 1. Directory Contents

* **[`Clouseau.tla`](Clouseau.tla)**: The canonical formal specification modeling actor lifecycles, LRU cache behavior, open waiter queues, Lucene commits, directory cleanup, and JInterface links.
* **Configurations**:
  * [`Clouseau.cfg`](Clouseau.cfg): Base configuration (2 PIDs, 2 Paths, 2 Waiters, LRUCapacity = 1).
  * [`Clouseau_Stress.cfg`](Clouseau_Stress.cfg): High-concurrency profile with deep waiter queues and larger LRU capacity.
  * [`Clouseau_Starvation.cfg`](Clouseau_Starvation.cfg): Resource starvation profile stressing rapid LRU evictions and tight PID recycling.

---

## 2. System Model & State Mapping

The TLA+ model maps directly to Clouseau's Scala/ZIO actors and its Erlang OTP client (Dreyfus in CouchDB):

| Subsystem | Scala / Erlang Source | TLA+ Model Entities |
| :--- | :--- | :--- |
| **LRU Cache & Lifecycles** | `IndexManagerService.scala` | `lruMap`, `lruOrder`, `pidState` (`opening`, `running`, `terminating`, `dead`) |
| **Concurrent Open Queue** | `IndexManagerService.scala` (`waiters`) | `waiters`, `waiterStatus`, `waiterReceivedPid` |
| **Erlang Links & Monitors** | `node.link(peer, pid)` / process monitors | `erlangLinks`, `clouseauLinks` |
| **Lucene Commits & Purges** | `IndexService.scala` | `luceneDocs`, `pendingSeq`, `committedSeq`, `purgeSeq`, `pendingPurgeSeq` |
| **Storage & Directory Cleanup**| `NativeFSLock`, `IndexCleanupService.scala` | `fsLockHeld`, `fsDirState` (`exists`, `deleting`), `fsCorrupted` |

---

## 3. Key Invariants Checked

The specification checks domain-level safety properties across all reachable states:

1. **`NoStalePidOnReopen`**: An index client in Dreyfus must never receive a PID that is in the `"dead"` or terminating state.
2. **`JInterfaceLinkSymmetry`**: Active cross-node links must be mutually registered between Dreyfus and Clouseau (`p \in erlangLinks[path] <=> path \in clouseauLinks[p]`).
3. **`ActiveIndexValidDir`**: Active running indices in the LRU cache must always point to valid, non-corrupted directory paths (`"exists"`).

---

## 4. Running Model Checking

Model checking can be run directly using Leslie Lamport's `tla2tools.jar`:

```bash
# 1. Base concurrency profile (rapidly detects race conditions and link asymmetry)
java -cp tla2tools.jar tlc2.TLC tla/Clouseau.tla -config tla/Clouseau.cfg -simulate num=10000

# 2. High-concurrency stress profile
java -cp tla2tools.jar tlc2.TLC tla/Clouseau.tla -config tla/Clouseau_Stress.cfg -simulate num=10000

# 3. Resource starvation & fairness profile
java -cp tla2tools.jar tlc2.TLC tla/Clouseau.tla -config tla/Clouseau_Starvation.cfg -simulate num=10000
```
