# Clouseau

Clouseau uses Scalang to expose Lucene functionality via erlang-like nodes.

## Running a local dev cluster

In separate terminal windows, run each of these commands;

```
mvn scala:run -Dlauncher=clouseau1
```

```
mvn scala:run -Dlauncher=clouseau2
```

```
mvn scala:run -Dlauncher=clouseau3
```

## Running in production

Build Clouseau and move to `/opt/clouseau`:

```
git clone https://github.com/cloudant-labs/clouseau.git
cd clouseau
mvn package
mkdir -p /opt/clouseau
mv target/clouseau-2.10.0-SNAPSHOT.zip /opt/clouseau
unzip -j /opt/clouseau/clouseau-2.10.0-SNAPSHOT.zip -d /opt/clouseau/lib
```

_Avoid the `mvn package` later and distribute the .zip to your nodes._

Start epmd:

```
setenv ERL_FLAGS="-name clouseau@fqdn -setcookie monster"
epmd -daemon
```

Config (in `/opt/clouseau/etc/clouseau.ini`):

```
[clouseau]
name=clouseau@fqdn
cookie=monster
dir=/my-index-dir
```

Configure logging (in `/opt/clouseau/etc/log4j.properties`):

```
log4j.rootLogger=INFO, stdout

log4j.appender.stdout=org.apache.log4j.ConsoleAppender
log4j.appender.stdout.layout=org.apache.log4j.PatternLayout
log4j.appender.stdout.layout.ConversionPattern=[%d] %p %m (%c)%n
```

Start Clouseau:

```
java -Dlog4j.configuration=file:/opt/clouseau/etc/log4j.properties \
    -cp /opt/clouseau/etc:/opt/clouseau/lib/* \
    com.cloudant.clouseau.Main \
    /opt/clouseau/etc/clouseau.ini
```
