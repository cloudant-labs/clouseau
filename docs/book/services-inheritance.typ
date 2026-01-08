#import "@preview/pintorita:0.1.4"

#let services-inheritance-diagram = ```pintora
classDiagram
  class Process {
    val runtime
    val name
    val self
    val node
  }

  class ProcessLike {
    <<trait>>
  }

  class Service {}

  class AddressableActor {}

  class  ClouseauSupervisor {
    Lifecycle of other services
  }
  class  IndexManagerService {
    Lifecycle of IndexService
  }
  class  IndexManager {
    Interface to Lucene
  }
  class  AnalyzerService {}
  class  InitService {
    Spawn test services
  }
  class  RexService {
    Maintenance API
  }


  AddressableActor <|-- ProcessLike
  ProcessLike <|-- Process
  Process <|-- Service

  Service <|-- ClouseauSupervisor
  Service <|-- IndexManagerService
  Service <|-- IndexService
  Service <|-- AnalyzerService
  Service <|-- InitService
  Service <|-- RexService
```
