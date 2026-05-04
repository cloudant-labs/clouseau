# Settings things up for [`mise`](https://mise.jdx.dev/)

## Install `mise`

You can install `mise` from [brew](https://brew.sh/) by following command.

```bash
brew install mise
```

## Activate `mise`

### Bash

```bash
echo 'eval "$(mise activate bash)"' >> ~/.bashrc
```

### Zsh

```zsh
echo 'eval "$(mise activate zsh)"' >> "${ZDOTDIR-$HOME}/.zshrc"
```

### Fish

If you installed `mise` via `brew`, it activates automatically, so you do not need to perform this step.

```fish
echo 'mise activate fish | source' >> ~/.config/fish/config.fish
```

## Parse the config file - [`mise.toml`](../mise.toml)

```bash
mise trust
```

## Install dependencies

Since `mise` entry hooks are enabled, `mise trust` should automatically install all dependencies.
If it fails to do so, please run the following command:

```bash
mise install
```

If you encounter issues, you can run the following command to diagnose common problems:

```bash
mise doctor
```

## Verify dependencies are installed

Once you have installed `mise`, you can run `cli verify` followed by `cli bootstrap`.
The `verify` command checks for any missing tools,
while `bootstrap` command guides you through the final setup steps.
Neither command will modify your system, so they are completely safe to run.
