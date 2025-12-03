#import "@preview/pintorita:0.1.4"

#let open-flow-diagram = ```pintora
sequenceDiagram
  title: Index Open Flow in Clouseau
  autonumber

  Dreyfus->>IndexManagerService: clouseau_rpc:open_index/3

  alt Is in LRU
    IndexManagerService->>Dreyfus: {ok, Pid}
  else Not in LRU
    IndexManagerService-->>Opener: Spawn opener
    activate Opener

    Opener-->>IndexService: IndexService.start/4
    activate IndexService

    IndexService->>Opener: Open Result

    alt OpenResult is Ok
      Opener->>IndexManagerService: {open_ok, Path, Peer, Pid}
      @start_note right of IndexManagerService
        1. Add to LRU
        2. Add monitor for Pid
        3. Create link between Peer and Pid
        .
      @end_note
      %%@note over IndexManagerService 1. Add to LRU 2. Add monitor for Pid 3. Create link between Peer and Pid
      %%@note IndexManagerService: hello
      IndexManagerService->>Dreyfus: {ok, Pid}
    else OpenResult is Error
      Opener->>IndexManagerService: {open_error, Path, Error}
      IndexManagerService->>Dreyfus: {error, Error}
    end

    deactivate Opener
  end

  note over Dreyfus,IndexService: Dreyfus interacts with IndexService
  deactivate IndexService
```
