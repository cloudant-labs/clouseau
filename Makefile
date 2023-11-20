.PHONY: help
# target: help - Print this help
help:
	@egrep "^# target: " Makefile \
		| sed -e 's/^# target: //g' \
		| sort \
		| awk '{printf("    %-20s", $$1); $$1=$$2=""; print "-" $$0}'

.PHONY: build
# target: build - Build package, run tests and create distribution
build: .asdf
	@mvn

.PHONY: clouseau1
# target: clouseau1 - Start local inistance of clouseau1 node
clouseau1: .asdf
	@mvn scala:run -Dname=$@

.PHONY: clouseau2
# target: clouseau2 - Start local inistance of clouseau2 node
clouseau2: .asdf
	@mvn scala:run -Dname=$@

.PHONY: clouseau3
# target: clouseau3 - Start local inistance of clouseau3 node
clouseau3: .asdf
	@mvn scala:run -Dname=$@

.PHONY: clean
# target: clean - Remove build artifacts
clean:
	@mvn clean

# ==========================
# Setting up the environment
# --------------------------

define CURRENT_SHELL
"$(shell basename $${SHELL})"
endef

define CURRENT_OS
"$(shell uname -s | tr '[:upper:]' '[:lower:]')"
endef

# Testing from fish
# set SHELL bash && make .asdf
# set SHELL fish && make .asdf
# set SHELL zsh && make .asdf
# set SHELL unsupported && make .asdf
# Testing from other shells
# SHELL=bash && make .asdf
# SHELL=fish && make .asdf
# SHELL=zsh && make .asdf
# SHELL=unsupported && make .asdf

ifndef ASDF_DIRENV_BIN
.asdf:
	@case $(CURRENT_SHELL)-$(CURRENT_OS) in \
       fish-darwin) echo "$$FISH_DARWIN_HELP" ;; \
       bash-darwin) echo "$$BASH_DARWIN_HELP" ;; \
       zsh-darwin) echo "$$ZSH_DARWIN_HELP" ;; \
       *) echo "$$CONTRIBUTE_SHELL_SETUP" ;; \
    esac
else
.asdf:
	@touch $@
	@touch .envrc
endif

define FISH_DARWIN_HELP
There are two steps which need to be done to set up environment.

  -  The asdf tool manager would need to be installed
  -  The direnv plugin for asdf manager would need to be installed


= Setting up asdf tool manager

  You can install asdf from brew by following command.

  ```
  brew install asdf
  ```

  Once you finish installation add following line to ~/.config/fish/config.fish

  ```
  source (brew --prefix asdf)/libexec/asdf.fish
  ```

  You can do it by copy/paste of the following line

  ```
  echo -e "\\nsource (brew --prefix asdf)/libexec/asdf.fish" >>  ~/.config/fish/config.fish
  ```

= Setting up direnv

  Run following commands to install direnv plugin

    ```
    asdf plugin-add direnv
    asdf install direnv latest
    asdf global direnv latest
    ```

  Once you have done with above commands add following to the ~/.config/fish/config.fish

    ```
    # Hook direnv into your shell.
    asdf exec direnv hook fish | source

    # A shortcut for asdf managed direnv.
    function direnv
      asdf exec direnv "$$argv"
    end
    ```

    You can do it by copy/paste of the following

    ```
    printf "\
    # Hook direnv into your shell.
    asdf exec direnv hook fish | source

    # A shortcut for asdf managed direnv.
    function direnv
      asdf exec direnv \\"\$$argv\\"
    end
    " >> ~/.config/fish/config.fish
    ```

= Enabling direnv integration

- After finishing the above two steps restart your shell.
- Run following `asdf direnv setup --shell fish --version latest`
- Restart your shell once again

endef
export FISH_DARWIN_HELP

define BASH_DARWIN_HELP

There are two steps which need to be done to set up environment.

  -  The asdf tool manager would need to be installed
  -  The direnv plugin for asdf manager would need to be installed


= Setting up asdf tool manager

  You can install asdf from brew by following command.

  ```
  brew install asdf
  ```

  Once you finish installation add following line to ~/.bash_profile

  ```
  source $$(brew --prefix asdf)/libexec/asdf.sh
  ```

  You can do it by copy/paste of the following line
  ```
  echo -e "\n. $$(brew --prefix asdf)/libexec/asdf.sh" >> ~/.bash_profile
  ```

= Setting up direnv

  Run following commands to install direnv plugin

    ```
    asdf plugin-add direnv
    asdf install direnv latest
    asdf global direnv latest
    ```

  Once you have done with above commands add following to the ~/.bash_profile

    ```
    # Hook direnv into your shell.
    eval "$$(asdf exec direnv hook bash)"

    # A shortcut for asdf managed direnv.
    direnv() { asdf exec direnv "$$@"; }
    ```

    You can do it by copy/paste of the following

    ```
    cat << EOF >> ~/.bashrc
    # Hook direnv into your shell.
    eval "$$(asdf exec direnv hook bash)"

    # A shortcut for asdf managed direnv.
    direnv() { asdf exec direnv "$$@"; }
    EOF
    ```

= Enabling direnv integration

- After finishing the above two steps restart your shell.
- Run following `asdf direnv setup --shell bash --version latest`
- Restart your shell once again

endef
export BASH_DARWIN_HELP

define ZSH_DARWIN_HELP

There are two steps which need to be done to set up environment.

  -  The asdf tool manager would need to be installed
  -  The direnv plugin for asdf manager would need to be installed


= Setting up asdf tool manager

  You can install asdf from brew by following command.

  ```
  brew install asdf
  ```

  Once you finish installation add following line to ~/.zshrc
  (or ${ZDOTDIR}/.zshrc if you use custom location).

  ```
  source $$(brew --prefix asdf)/libexec/asdf.sh
  ```

  You can do it by copy/paste of the following line
  ```
  echo -e "\n. $$(brew --prefix asdf)/libexec/asdf.sh" >> $${ZDOTDIR:-~}/.zshrc
  ```

= Setting up direnv

  Run following commands to install direnv plugin

    ```
    asdf plugin-add direnv
    asdf install direnv latest
    asdf global direnv latest
    ```

  Once you have done with above commands add following to the ~/.zshrc
  (or ${ZDOTDIR}/.zshrc if you use custom location).


    ```
    # Hook direnv into your shell.
    eval "$$(asdf exec direnv hook zsh)"

    # A shortcut for asdf managed direnv.
    direnv() { asdf exec direnv "$$@"; }
    ```

    You can do it by copy/paste of the following

    ```
    cat << EOF >>  ~/.zshrc
    # Hook direnv into your shell.
    eval "$$(asdf exec direnv hook zsh)"

    # A shortcut for asdf managed direnv.
    direnv() { asdf exec direnv "$$@"; }
    EOF
    ```

= Enabling direnv integration

- After finishing the above two steps restart your shell.
- Run following `asdf direnv setup --shell zsh --version latest`
- Restart your shell once again

endef
export ZSH_DARWIN_HELP


define CONTRIBUTE_SHELL_SETUP
  We detected $(CURRENT_SHELL) running on $(CURRENT_OS). Unfortunatelly
  documentation for this combination is not available. Contributions are welcome.
endef
export CONTRIBUTE_SHELL_SETUP