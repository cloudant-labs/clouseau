#import "@preview/pintorita:0.1.4"

#let components-diagram = ```pintora
componentDiagram

package "Clouseau" {
  [ClouseauSupervisorService]
  [IndexManagerService]
  [CleanupService]
  [IndexService]
  [AnalyzerService]

  ClouseauSupervisorService --> AnalyzerService
  ClouseauSupervisorService --> IndexManagerService
  ClouseauSupervisorService --> CleanupService
  IndexManagerService --> IndexService
}

IndexService --> Service

frame "ActorFramework" {
  [TypeFactory]
  [AddressableActor]
  [EngineWorker]
  [Exchange]
}

node "Facade" {
  package "Scalang" {
    [Service]
    [Process]
    [ProcessLike]

    [Adapter]
    [SNode]

    [Process] --> [ProcessLike]
    [Service] --> [Process]
  }

  [ClouseauNode]
  [ClouseauTypeFactory]

  [Configuration]
  [ClouseauMetrics]
  [LoggerFactory]
}

node "Foundation" {
  package "ZIO" {
    [logging]
    [metrics]
    [config]
    [fiber]
  }

  package "OTP" {
    [mailbox]
    [ProcessContext]
    [node]
  }

  package "jInterface" {
    [OtpMbox]
    [OtpNode]
  }
}

  [AddressableActor] --> [fiber]
  [AddressableActor] --> [ProcessContext]

  [ProcessContext] --> [mailbox]
  [EngineWorker] --> [node]

  [mailbox] --> [OtpMbox]
  [node] --> [OtpNode]

  [ProcessLike] --> [Adapter]
  [ProcessLike] --> [AddressableActor]

  [Adapter] --> [SNode]

  [LoggerFactory] --> [logging]
  [ClouseauMetrics] --> [metrics]
  [Configuration] --> [config]


```
