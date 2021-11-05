# clouseau

Expose Lucene features to erlang RPC.

## Setting up the development environment

We use a combination of [`direnv`](https://github.com/direnv/direnv) and [`asdf`](https://github.com/asdf-vm/asdf) to manage development environment.
The minimal set of tools you need to install manually is:

- xcode
- brew
- asdf
- git
- coreutils

1. install asdf itself
```
brew install asdf
```
2. integrate asdf with your shell
  - fish
   ```
   echo -e "\nsource $(brew --prefix asdf)/libexec/asdf.fish" >>  ~/.config/fish/config.fish
   ```
  - bash
   ```
   echo -e "\n. $(brew --prefix asdf)/libexec/asdf.sh" >> ~/.bash_profile
   ```
  - zsh
   ```
   echo -e "\n. $(brew --prefix asdf)/libexec/asdf.sh" >> ${ZDOTDIR:-~}/.zshrc
   ```
3. install direnv plugin
```
asdf plugin-add direnv
asdf install direnv latest
asdf global direnv latest
```
4. integrate direnv plugin with your shell
  - fish
   ```
   printf "\
   # Hook direnv into your shell.
   asdf exec direnv hook fish | source

   # A shortcut for asdf managed direnv.
   function direnv
     asdf exec direnv \"\$argv\"
   end
   " >> ~/.config/fish/config.fish
   ```
  - bash
   ```
   cat << EOF >> ~/.bashrc
   # Hook direnv into your shell.
   eval "$(asdf exec direnv hook bash)"

   # A shortcut for asdf managed direnv.
   direnv() { asdf exec direnv "$@"; }
   EOF
   ```
  - zsh
   ```
   cat << EOF >>  ~/.zshrc
   # Hook direnv into your shell.
   eval "$(asdf exec direnv hook zsh)"

   # A shortcut for asdf managed direnv.
   direnv() { asdf exec direnv "$@"; }
   EOF
   ```
5. enable `use asdf` feature
```
echo 'source "$(asdf direnv hook asdf)"' >> ~/.config/direnv/direnvrc
```
6. exit from the current shell or start a new one
7. `cd` into the directory where you checked out `clouseau` and do `direnv allow`
8. do `make tools` (we need it only when we add new tools into `.tool-versions`)

Now every time you `cd` into the project directory the correct versions of tools
will be activated.

### Custom Java

In some cases version of Java is not available in asdf-java.
There is a trick which makes globally available version recognized by
asdf.

```
ln -s /Library/Java/JavaVirtualMachines/jdk1.7.0_80.jdk/Contents/Home ~/.asdf/installs/java/oracle-1.7.0
asdf reshim java
```

## Building distribution

Just run `make` to get `target/clouseau-{version}-SNAPSHOT.zip` file.

## Running locally

To run single node instance use `make clouseau1`.

Usually for testing all three nodes might be needed. In such case run following
from separate terminals:

```
make clouseau1
make clouseau2
make clouseau3
```

## Changing tools version

The list of tools are configured in a `.tool-versions` file. Make sure you
run `make tools` every time you add new tools. You don't need to do it when
there is just a version change.

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

## Changing the clouseau options
In order to change the `clouseau` config, an operator must edit the role (corresponding to the cluster) in the `chef-repo`. And push the changes to chef, follow these guides to apply the changes made in chef repo:

[chef-workflow](https://github.com/cloudant/ops/blob/master/guides/config-management/chef-workflow.md)

[knife configuration](https://github.com/cloudant/ops/blob/master/guides/config-management/Knife-Configuration.md)

Example role file (showed only the clouseau section) which resulted in the configuration mentioned in the above section:
```
"clouseau": {
      "close_if_idle": true,
      "idle_check_interval_secs": 600,
      "min_memory": "12G",
      "max_memory": "15G",
      "max_indexes_open": 15000
    }
```


## Deep dive into clouseau options
Tweaking these clouseau options, by updating role file corresponding to the cluster, would enable us to handle the various work load scenarios and allow us to better utilize the available hardware resources. In the following sections, will go over these options and explain the significance of each one of them and provide some guidelines on how to tune them.

### max_memory
As you know, clouseau's run time is Java and `max_memory` option allows us to tune the maximum heap size for the JVM. By default this is set to 2GB and is recommended to tune it based on the the work load. The amount of heap usage depends on the number of  active (opened) search indices and also the search load (sorting etc). The amount of heap required usually correlates with the `max_indexes_open` settings, so if we want to have higher number of indices opened then we need higher value set for `max_memory`.

The recommendation is to keep `max_memory` to maximum of one third of the available memory and to never allocate more than 50% of the total available memory. So if the nodes on cluster have 30GB of memory available, then limit `max_memory` setting to 10GB and if that's not enough and the user workload still requires more memory then we may increase it to 15GB (50% of available). But be cautious when going over the `one third` of the available memory as it could result in less memory available for erlang runtime and the OS.

### min_memory
This setting is optional and is recommended to set in the cases of higher maximum heap size (> 8GB). And if set, keep it at 80% of `max_memory` and can't be more than `max_memory`. This option allows the JVM to set initial memory when the JVM started (clouseau start) to avoid dynamic heap resizing and lags.

### max_indexes_open
This option allows to specify the maximum number indices opened at a given point of time. As clouseau uses Lucene, and each index opened by lucene has an overhead (mainly heap), and if we don't close the indices then the JVM will run out of memory and hence this option to limit the number of opened indices. By default this is set to 200, and for many large deployments where the customers have many active indices, we had to significantly increase this number(in the above example it's set to 15000). Once we reach this limit then `clouseau` will close the oldest index (in terms of when its opened).

### close_if_idle && idle_check_interval_secs
These options allows us to close the search indices if there is no activity within a specified interval. As mentioned in the above section about the `max_indexes_open`, when number of indices opened reaches the `max_indexes_open` limit then clouseau will close the index that was opened first even if there is an activity on that index. This was leading to errors and hence added this option to close the idle indices first.
Basically we will close the idle indices so that we can avoid reaching the limit specified in `max_indexes_open`. So that we will close the idle indices first and if we still reach the limit then clouseau will close the oldest index (based on the open time).

If `close_if_idle` is set to true, then it will start monitoring the activity of the indices and will close the index if there is no activity in two consecutive idle check intervals. By default the `idle_check_interval_secs` is 300 seconds (5 minutes) and can be overridden by specifying the `idle_check_interval_secs`. In the example configuration specified in the above section, this was set to 600 seconds, which will close the index if there is no activity for anywhere between 601 to 1200 seconds.

This variation (instead of fixed) in the index closing due to inactivity is a result of the implementation approach, where during the first index idle check interval we mark the index as idle and close that index if the state is still idle during the next check. And if there is any activity between the two `index idle check` intervals then it will not be closed.


## Taking and analyzing a heap dump of clouseau
1. Put a node into the maintenance mode (inform the client if need). In remsh:

      ```
      s:maintenance_mode("heap dump of clouseau").
      ```
2. Find the clouseau pid (process: "java [...] com.cloudant.clouseau.Main /opt/cloudant/etc/clouseau.ini" ):

     ```
     ps aux | grep clouseau
     ```
3. cd to /srv, so that the heap dump is stored in `/srv/heap.bin` file.
4. Take a heap dump:

      ```
      sudo jmap -F -dump:live,format=b,file=heap.bin <ClouseauPID>
      ```
5. After a dump is taken, put the node back into the production mode. In remsh:

      ```
      s:production_mode().
      ```
6. Copy heap.bin file from the cloudant node to your local machine:

      ```
      rsync -avz db<X>.<CLUSTER>.cloudant.com:/srv/heap.bin .
      ```

7. Analyze the heap dump using `visualVM` tool or Eclipse memory analyzer tool(http://www.eclipse.org/mat/downloads.php).
