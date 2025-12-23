
#import "@preview/pintorita:0.1.4"
#import "@preview/biz-report:0.2.0": report

#import "./services-inheritance.typ": services-inheritance-diagram
#import "./components.typ": components-diagram
#import "./link-monitors.typ": link-monitors-diagram
#import "./open-flow.typ": open-flow-diagram

#show: report.with(
  title: "Clouseau 3.x",
  publishdate: "November 2025",
  // Uncomment if/when you find a good logo image to display
  // I did comment the line out to avoid legal problems
  // mylogo: image("./couchdb-svgrepo-com.svg", width: 35%),
  myfeatureimage: image("./room.jpg", height: 6cm),
  myvalues: "CouchDB | Lucene | Dreyfus | Clouseau",
  mycolor: rgb(0, 166, 156),
  myfont: "New Computer Modern"
)
#set par(first-line-indent: 1em)

#set par(justify: true)

#show raw.where(lang: "pintora"): it => pintorita.render(it.text)

#pagebreak()

Apache®, Apache CouchDB, CouchDB, and the logo are either registered
trademarks or trademarks of the Apache Software Foundation in the
United States and/or other countries.
No endorsement by The Apache Software Foundation is implied by the use
of these marks.

#pagebreak()

= Description

Clouseau is a Lucene-based full-text search engine integrated with
CouchDB through Dreyfus.
It provides efficient indexing and querying capabilities for CouchDB
documents, enabling advanced search features.
The current implementation has served well for years, but it is built
on an unsupported language and outdated runtime components, which
introduces significant operational risks.
Maintenance has become increasingly difficult, and the system is prone
to instability under load.
The aging technology stack limits the ability to evolve the component.
Previous attempts to rewrite Clouseau failed due to time overruns,
complex deployment processes, and lack of confidence in compatibility
with existing APIs and data formats.
These failures highlighted the need for a modernization strategy that
prioritizes stability, backward compatibility, and incremental change
over a full rewrite.
The Clouseau 3.x release aims to address these challenges by
introducing a modern foundation while preserving critical components
such as the Lucene integration layer, on-disk index format, and
external APIs.
This approach ensures that the new version can be deployed as a
drop-in replacement, minimizing operational risk and avoiding costly
re-indexing or client-side changes.

= Goals of the Clouseau 3.x Release

The primary goal of this modernization effort is to stabilize and
future-proof the search service without disrupting existing
functionality or introducing unnecessary risk.
Search is a critical component, yet its current implementation relies
on an unsupported language, making maintenance increasingly difficult.
Previous rewrite attempts failed due to time overruns, deployment
complexity, and lack of confidence in compatibility.
To avoid repeating these mistakes, this project focuses on incremental
modernization with strict constraints.

- Preserve API Compatibility.
  Ensure that all external APIs remain unchanged.
  This guarantees that clients and upstream systems can continue
  operating without modifications, reducing deployment risk and
  avoiding costly integration work.

- Maintain Existing Lucene Integration.
  Avoid changes to the Scala glue code that communicates with Lucene.
  This minimizes complexity and ensures that proven indexing and
  search logic remains intact.

- Preserve On-Disk Data Format.
  Do not alter existing index files.
  This prevents re-indexing, which would otherwise introduce downtime
  and operational risk.

- Deliver a Drop-In Replacement.
  The new version of Clouseau must be deployable as a drop-in
  replacement for the current system.
  This simplifies rollout and rollback strategies, making deployment
  safer and faster.

- Use a Modern Foundation.
  Replace the unsupported components with a modern, well-supported
  technology stack.
  This improves maintainability, security, and long-term viability.

- Retain Business Logic.
  Keep the existing business logic intact to reduce functional risk
  and ensure predictable behavior during migration.

- Avoid Building Custom Infrastructure.
  Do not implement a custom asynchronous scheduler or other low-level
  components.
  Instead, leverage mature libraries and frameworks to reduce
  complexity and improve reliability.

- Enable Future Enhancements.
  While the initial phase focuses on stability and compatibility, the
  new foundation should allow future improvements such as Lucene
  upgrades, API enhancements, and performance optimizations without
  requiring another full rewrite.

#pagebreak()

== Updated and Migrated Components

#table(
  columns: 4,
  [*Dependency*], [*Old Version*], [*Replacement*], [*Replacement version*],
  [Java], [JDK 7], [Java], [JDK 21.0.12],
  [Scala], [2.9.1], [Scala], [2.13.16],
  table.cell(rowspan: 2)[com.yammer.metrics], table.cell(rowspan: 2)[2.2.0],
    [zio.metrics], [2.0.0],
    [micrometer], [1.16.0],
  [jetlang], [0.2.12], [zio], [2.1.16],
  [scalang], [2.9.1], [-], [-],
  table.cell(rowspan: 2)[org.slf4j], table.cell(rowspan: 2)[1.7.32],
    [zio.logging], [2.5.0],
    [tinylog], [2.7.0],
  [commons-configuration], [1.8], [zio.config], [4.0.4],
)

= Architecture


The project follows a layered architecture where all components are
organized into distinct layers:

- Foundation Layer.
  This layer includes core dependencies, such as ZIO and OTP
  abstractions, which form the foundation of our application.
  The motivation behind splitting the foundation into core and otp was
  to enable potential future replacements of communication protocol
  while retaining the existing functionality.

- Facade Layer.
  This layer consists of `scalang` emulation and interfaces
  converters, which act as a facade for the application, allowing us
  to run unmodified buisness logic from previous version of the
  project.

- Clouseau Layer.
  The main application logic resides in this layer, primarily made up
  of unmodified service classes like `ClouseauSupervisor`,
  `IndexManagerService`, `IndexManager`, and `AnalyzerService`.
  These classes are responsible for implementing the core business
  functionality.

To achieve the goal of potentially replacing the existing
`jInterface`, the project includes a split of the foundation layer
into two parts: `core` and `otp`.
This allows us to separate the core functionality of the application
from the specific implementation details related to the communication
protocol.
It also makes it easier for developers to understand the structure of
the project and make targeted modifications while reducing the
potential impact on other parts of the system.


= ZIO Integration

ZIO is a modern, purely functional library for asynchronous and
concurrent programming in Scala.
It provides a powerful effect system that enables developers to write
type-safe, composable, and resource-safe code without relying on
traditional thread-based concurrency models.
At its core, ZIO introduces fibers, which are lightweight, cooperative
threads managed by the ZIO runtime.
Fibers allow applications to achieve massive scalability and low
latency while avoiding common pitfalls of concurrency such as
deadlocks, race conditions, and resource leaks.
Unlike traditional approaches that depend on locks or blocking
operations, ZIO uses a lock-free concurrency model based on atomic
operations and structured concurrency principles, ensuring predictable
behavior and safe cancellation of tasks.

ZIO also includes a rich ecosystem of libraries that extend its
capabilities:

- *ZIO Streams* for processing large or infinite data streams with
   backpressure and composable operators.

- *ZIO Config* for type-safe configuration management from multiple
   sources.

- *ZIO Logging* for structured, context-aware logging across
   asynchronous boundaries.

- *ZIO STM* for software transactional memory, enabling safe and
   composable concurrent state updates without locks.

- *ZIO Test* for deterministic testing of concurrent code and
   property-based testing.

In our modernization project, ZIO forms the core of the Foundation
Layer, providing structured concurrency, resource management, and
effect handling.
We leverage ZIO's fiber-based runtime to replace the
legacy scheduling approach, ensuring that all operations run within
well-defined scopes.
This guarantees that resources like file handles, Lucene directories,
and network sockets are properly acquired and released, even in the
presence of failures.
By using ZIO's `ZManaged` and finalizer constructs, we eliminate the
risk of resource leaks and simplify error handling.

The decision to adopt ZIO aligns with our goal of creating a modern,
future-proof foundation without rewriting business logic.
ZIO integrates seamlessly with Scala, allowing us to retain existing
service classes while replacing the underlying concurrency and
resource management layer.
This approach provides immediate benefits -- such as improved
stability and observability -- while laying the groundwork for future
enhancements like reactive streaming, advanced scheduling.

#pagebreak()

= Components Diagram

The component diagram illustrates the modular architecture of Clouseau
and its supporting layers.
At the top is the Clouseau package, which contains the primary
business logic services responsible for managing the lifecycle of
search operations.
The `ClouseauSupervisorService` acts as the top-level supervisor,
responsible for restart of other services if they would terminate.
The `IndexManagerService` is responsible for managing index lifecycles
and delegates actual index operations to `IndexService`, which
interacts directly with Lucene through the Scala glue layer.
`AnalyzerService` provides text analysis capabilities, while
`CleanupService` handles index deletion.

- The `Facade` layer, represented by the Scalang package, bridges
  Clouseau's business logic with the underlying actor system.  This
  layers adapts API provided by the `ActorFramework` to the needs of
  Clouseau layer.
  It includes abstractions such as `Service`, `Process`, and
  `ProcessLike`, which are re-implementation of the abstractions
  provided by the Scalang (the framework we are replacing).
  Additional components like `Adapter` and `SNode` facilitate
  integration with the distributed runtime.
  This layer also includes utility components such as `ClouseauNode`,
  `ClouseauTypeFactory`, `Configuration`, `ClouseauMetrics`, and
  `LoggerFactory`, which provide node management, configuration
  handling, metrics collection, and logging.

- The `ActorFramework`, which provides concurrency and distribution
  primitives.
  Components like `AddressableActor`, `EngineWorker`, `Exchange`, and
  `TypeFactory` implement the actor model, enabling message-driven
  communication and fault-tolerant execution across nodes.

- Finally, the `Foundation` layer provides essential runtime.
  The ZIO package offers structured concurrency, logging, metrics, and
  configuration management.
  The OTP package provides connectivity over Erlang Distribuition
  Protocol.
  The OTP package uses `jInterface` package which provides low-level
  interoperability with Erlang nodes.

Overall, this layered design separates concerns clearly: the Clouseau
package focuses on search-specific logic, the `ActorFramework`
provides concurrency and messaging, the `Facade` layer integrates
these with the actor runtime, and the `Foundation` layer delivers
robust primitives for process management and communication.

#box(components-diagram)

#pagebreak()

= Inheritance diagram

In Clouseau's architecture, all business logic classes share a common
inheritance pattern that begins with the `Service` class and
ultimately implements the `Actor` trait through an inheritance chain.
At the foundation, the `ProcessLike` trait defines the essential
behavior expected from any actor-like component.
This trait is extended by the `Process` class, which introduces core
attributes like runtime, name, self, and node.
Building on `Process`, the `Service` class acts as the primary
abstraction for all business logic components.
It encapsulates common service-level responsibilities such as
initialization, message handling, and graceful shutdown, and most
importantly adaptiation between async and sync parts of the system.
Every major component in Clouseau -- such as `ClouseauSupervisor`,
`IndexManagerService`, `IndexService`, `AnalyzerService`,
`InitService`, and `RexService` -- inherits from Service.
Through this chain, each service class becomes an `AddressableActor`
indirectly, because `Process` and `ProcessLike` integrate with the
actor model provided by the underlying runtime.
This is crucial for Clouseau's distributed nature: actors can be
addressed, monitored, and linked across nodes.
The inheritance hierarchy was chosen to reflect the same structure
provided by Scalang (actor framework used by Clouseau 2.x).
By rooting all business logic in Service and connecting it to the
actor system via `Process` and `ProcessLike`, Clouseau achieves a
clean separation of concerns: the actor framework handles concurrency
and distribution, while service classes implement domain-specific
functionality.
Keep in mind that as we rewrite services in async manner the
inheritance hierarchy would change and eventually the `Service` would
be removed, which would simplify the system a lot.
A diagram representing the relationships and hierarchies between
several key classes in the architecture. The stack shown on the
diagram only covers Clouseau layer and facade layer.

#box(services-inheritance-diagram)

#pagebreak()

= Index lifecycle management

This index lifecycle management diagram illustrates the interaction
between Dreyfus and Clouseau during index lifecycle management,
focusing on how ETS tables and LRU cache coordinate with monitors and
links.
On the Dreyfus side, `dreyfus_index_manager` maintains two ETS tables:
`BY_INDEX`, which maps `{Db, Index}` to process IDs, and `BY_PID`,
which tracks active processes for cleanup and routing.
When an index is requested, `dreyfus_index_manager` either retrieves
the PID from ETS or calls `clouseau_rpc:open_index/3` to forward the
request to Clouseau.
On the Clouseau side, `IndexManagerService` checks the `OpenIndexLRU`
cache for an existing index actor; if absent, it spawns a new
`IndexService` actor and adds it to the LRU.
Each opened index is represented by two actors -- `dreyfus_index` in
Erlang and `IndexService` in Scala -- linked together so that
termination of one propagates to the other.
Monitors are established on both sides: `dreyfus_index_manager`
monitors `dreyfus_index`, and `IndexManagerService` monitors
`IndexService`.
This design ensures consistency: if `dreyfus_index` dies, the manager
receives a `DOWN` signal and removes the entry from ETS, while the
link triggers an `EXIT` signal to terminate `IndexService`, which in
turn is removed from the LRU.
Conversely, if `IndexService` dies, `IndexManagerService` cleans up
the LRU entry, and the link causes `dreyfus_index` to exit, prompting
ETS cleanup.
This bidirectional monitoring and linking strategy guarantees that
stale references are eliminated promptly, maintaining system integrity
across distributed components.
However, this design introduces a split-brain scenario because it
requires maintaining the index-to-PID mapping in two separate
locations.
If either side delays the eviction of a terminated actor,
inconsistencies can arise.
In the worst case, these discrepancies may lead to an unrecoverable
state where the system cannot reconcile the conflicting mappings.
This design remains unchanged from the previous version of the
Clouseau.
Re-architecting this aspect of the system was explicitly out of scope
for the modernization project.

#box(link-monitors-diagram)

= Event Flows

== Open index flow

Before diving into the flow, let's outline the key components:

- *Dreyfus*: An Erlang application acting as the bridge between
  CouchDB and Clouseau.
  It manages RPC calls and index lifecycle.

- *Clouseau*: A Scala-based service that wraps Lucene functionality
  and exposes it to Dreyfus.

- *IndexManagerService*: Maintains an LRU cache of open indexes and
  coordinates opening/closing.

- *IndexService*: Handles Lucene operations like opening directories,
  initializing writers/searchers.

- *Opener*: A temporary process responsible for spawning
  `IndexService` and reporting results.

- *Lucene*: The underlying library providing full-text indexing and
  search capabilities.

CouchDB opens an index through the following flow.
Dreyfus sends a request to Clouseau using `clouseau_rpc:open_index/3`.
This is a `gen_server` call, which on the Erlang side looks like
`gen_server:call({main, 'clouseau@127.0.0.1'}, {open, self(), Path,
Options})`.
The call asks Clouseau to open or retrieve an index process for a
given index path.
Clouseau's `IndexManagerService` receives the request and first checks
its LRU cache.
If the index is already open, it immediately returns `{ok, Pid}` to
Dreyfus -- no additional work is required.
If the index is not cached, the manager spawns a lightweight process
called the `Opener`.
The `Opener` is short-lived and its sole responsibility is to start
the actual index worker.
It invokes `IndexService.start/4` with the index path and
configuration options, which creates the `IndexService` actor.
Inside `IndexService`, Lucene resources are initialized: the
`FSDirectory` is opened, the `IndexWriter` and `IndexSearcher` are set
up, and the appropriate analyzer is applied based on index settings.
This stage involves disk I/O and lock acquisition, which are critical
for consistency.
If initialization succeeds, `IndexService` reports success to the
`Opener`.
The `Opener` then sends `{open_ok, Path, Peer, Pid}` back to
`IndexManagerService`.
At this point, the manager updates its LRU cache, establishes
monitoring for the new process, and links the peer and index process
to ensure fault tolerance.
Finally, it responds to Dreyfus with `{ok, Pid}`.
If an error occurs during initialization, the `Opener` sends
`{open_error, Path, Error}` to the manager, which forwards `{error,
Error}` back to Dreyfus.
Cleanup logic in `IndexService.exit()` ensures Lucene resources are
released properly, which is essential when directories are moved or
deleted or when Lucene write locks fail.

#box(open-flow-diagram)


= Roadmap and future improvements

To enhance Clouseau's efficiency and better align it with the
asynchronous nature of the ZIO framework, there are proposed
improvements for the facade layer:

- Currently, business logic is implemented using classes with
  synchronous methods that get invoked when an event is retrieved from
  an actor's mailbox.
  This approach introduces inefficiencies due to the interaction
  between the asynchronous world provided by ZIO and the synchronous
  business logic.
  To address this issue, we plan to refactor Clouseau classes from
  their existing design where they are inherit of `Service`, towards
  utilizing the `AddressableActor` class instead.

  This architectural improvement will enable better integration with
  ZIO's reactive programming model and create a more efficient
  Clouseau implementation.
  Also it would allow as to remove lots of code related to facade
  layer.
  In essence, we aim to create a more cohesive design that leverages
  the power of asynchronous processing, offering improved performance
  and responsiveness.

- Replace JMX reporter with Prometheus endpoint.
  The goal for first release of 3.0.0 was to be backward compatible
  with legacy Clouseau.
  Once we deprecate legacy Clouseau we can abandon JMX, this would
  simplify metrics collection a lot.
  Since we don't need to restructure the beans.

- Re-architect the interaction between Dreyfus and Clouseau to avoid
  distributed state and improve reliability.
  Currently both dreyfus and Clouseau maintain mapping of file path to
  PID.
