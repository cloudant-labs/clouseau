# Settings things up for `bash`

There are two steps which need to be done to setup environment.

1. The `asdf` tool manager would need to be installed
2. The `direnv` plugin for `asdf` manager would need to be installed

You can install `asdf` from [brew](https://brew.sh/) by following command.

```shell
brew install asdf
```

Once you install `asdf` you can either continue following the steps outlined in the current document or run `scripts/cli verify` followed by `scripts/cli bootstrap`. The `verify` command would check if you are missing any important tools we depend on. While `bootstrap` command would guide you through the steps needed to finish setup. It will not modify anything for you. So it is totally safe to run.

## `bash-asdf`: setup `asdf` tool manager

Add following line to `~/.bash_profile`

```
source $(brew --prefix asdf)/libexec/asdf.sh
```

You can do it by copy/paste of the following line

```
echo -e "\n. $(brew --prefix asdf)/libexec/asdf.sh" >> ~/.bash_profile
```

Please restart shell and re-run `scripts/cli bootstrap` once you do it

---

## `bash-direnv`: setup `direnv` plugin

**Note**: Keep in mind that the `direnv` plugin can conflict with global installation of `direnv` you might need to remove your existent configuration.

Run following commands to install `direnv` plugin

```shell
asdf plugin-add direnv
asdf install direnv latest
asdf global direnv latest
```

Once you have done with above commands add following to the `~/.bash_profile`

```

# Hook direnv into your shell.
eval "$(asdf exec direnv hook bash)"

# A shortcut for asdf managed direnv.
direnv() { asdf exec direnv "$@"; }
```

You can do it by copy/paste of the following

```
cat << EOF >> ~/.bashrc
# Hook direnv into your shell.
eval "$(asdf exec direnv hook bash)"

# A shortcut for asdf managed direnv.
direnv() { asdf exec direnv "$@"; }
EOF
```

---

# Settings things up for `fish`

There are two steps which need to be done to setup environment.

1. The `asdf` tool manager would need to be installed
2. The `direnv` plugin for `asdf` manager would need to be installed

You can install `asdf` from [brew](https://brew.sh/) by following command.

```shell
brew install asdf
```

Once you install `asdf` you can either continue following the steps outlined in the current document or run `scripts/cli verify` followed by `scripts/cli bootstrap`. The `verify` command would check if you are missing any important tools we depend on. While `bootstrap` command would guide you through the steps needed to finish setup. It will not modify anything for you. So it is totally safe to run.

## `fish-asdf`: setup `asdf` tool manager

Add following line to `~/.config/fish/config.fish`

```
source (brew --prefix asdf)/libexec/asdf.fish
```

You can do it by copy/paste of the following line

```
echo -e "\nsource (brew --prefix asdf)/libexec/asdf.fish" >>  ~/.config/fish/config.fish
```

Please restart shell and re-run `scripts/cli bootstrap` once you do it

---

## `fish-direnv`: setup `direnv` plugin

**Note**: Keep in mind that the `direnv` plugin can conflict with global installation of `direnv` you might need to remove your existent configuration.

Run following commands to install `direnv` plugin

```shell
asdf plugin-add direnv
asdf install direnv latest
asdf global direnv latest
```

Once you have done with above commands add following to the `~/.config/fish/config.fish`

```
# Hook direnv into your shell.
asdf exec direnv hook fish | source

# A shortcut for asdf managed direnv.
function direnv
    asdf exec direnv "$argv"
end
```

You can do it by copy/paste of the following

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

---

# Settings things up for `zsh`

There are two steps which need to be done to setup environment.

1. The `asdf` tool manager would need to be installed
2. The `direnv` plugin for `asdf` manager would need to be installed

You can install `asdf` from [brew](https://brew.sh/) by following command.

```shell
brew install asdf
```

Once you install `asdf` you can either continue following the steps outlined in the current document or run `scripts/cli verify` followed by `scripts/cli bootstrap`. The `verify` command would check if you are missing any important tools we depend on. While `bootstrap` command would guide you through the steps needed to finish setup. It will not modify anything for you. So it is totally safe to run.

## `zsh-asdf`: setup `asdf` tool manager

Add following line to `~/.zshrc` (or `${ZDOTDIR}/.zshrc` if you use custom location).

```
source $(brew --prefix asdf)/libexec/asdf.sh
```

You can do it by copy/paste of the following line

```
echo -e "\n. $(brew --prefix asdf)/libexec/asdf.sh" >> ${ZDOTDIR:-~}/.zshrc
```

Please restart shell and re-run `scripts/cli bootstrap` once you do it

---

## `zsh-direnv`: setup `direnv` plugin

**Note**: Keep in mind that the `direnv` plugin can conflict with global installation of `direnv` you might need to remove your existent configuration.

Run following commands to install `direnv` plugin

```shell
asdf plugin-add direnv
asdf install direnv latest
asdf global direnv latest
```

Once you have done with above commands add following to the  `~/.zshrc` (or `${ZDOTDIR}/.zshrc` if you use custom location).

```
# Hook direnv into your shell.
eval "$(asdf exec direnv hook zsh)"

# A shortcut for asdf managed direnv.
direnv() { asdf exec direnv "$@"; }
```

You can do it by copy/paste of the following

```
cat << EOF >>  ~/.zshrc
# Hook direnv into your shell.
eval "$(asdf exec direnv hook zsh)"

# A shortcut for asdf managed direnv.
direnv() { asdf exec direnv "$@"; }
EOF
```

---

# Common steps

## `direnv`: enable `use asdf` feature

Finally, when both `asdf` and `direnv` are configured you need to tell `direnv` where to find `asdf` provided helpers. This can be done by adding the following line to `~/.config/direnv/direnvrc`.

```
source "$(asdf direnv hook asdf)"
```

You can also do it by copy/paste of the following

```
echo 'source "$(asdf direnv hook asdf)"' >> ~/.config/direnv/direnvrc
```
