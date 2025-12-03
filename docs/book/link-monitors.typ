#import "@preview/pintorita:0.1.4"

#let link-monitors-diagram = ```pintora
componentDiagram
  package "Dreyfus" {
    [dreyfus_index_manager]
    [dreyfus_index]
    component "ETS" {
      [BY_PID]
      [BY_INDEX]
    }
  }

  package "Clouseau"  {
    [IndexManagerService]
    [IndexService]
    component "LRU" {
      [OpenIndexLRU]
    }
  }

  component "clouseau_rpc:open_index/3" as open_index

  package "RPC" {
    [open_index]
  }

  %% insert/lookup
  dreyfus_index_manager --> BY_PID
  %% map (Db,Index) -> Pid
  dreyfus_index_manager --> BY_INDEX
  %% request open/get index
  dreyfus_index_manager --> open_index
  %% forward open request
  open_index --> IndexManagerService

  %% check/add entry (Pid) and evict if needed
  IndexManagerService --> OpenIndexLRU
  %% spawn new index
  IndexManagerService --> IndexService

  %% ' monitors & links (runtime)
  dreyfus_index_manager --> dreyfus_index : monitor child
  IndexManagerService ..> IndexService : monitor index
  dreyfus_index ..> IndexService : link peer <-> index
```
