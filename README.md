# clouseau

Expose Lucene features to erlang RPC.

## Configuration options
This guide explains the various clouseau configuration options available, and how to use them to tune clouseau performance and scalability. There are two categories of clouseau options, first category is about tuning the JVM (ex: Xmx) and other category of options that go into clouseau.ini. 

Clouseau configuration options (as determined by the relevant role in chef-repo) are stored in `/opt/clouseau/etc/clouseau.ini` and some options (about JVM tuning) go into the command used to start and stop clouseau.

Example clouseau configuration options in clouseau.ini:
```
[clouseau]
max_indexes_open=15000
close_if_idle=true
idle_check_interval_secs=600
```

## Running a local dev cluster

In separate terminal windows, run each of these commands;

mvn scala:run -Dlauncher=clouseau1

mvn scala:run -Dlauncher=clouseau2

mvn scala:run -Dlauncher=clouseau3
