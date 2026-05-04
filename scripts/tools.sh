#!/bin/bash
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# call it as
# result=( $(tools::requires "${os}") )
# where `os` is one of `darwin | linux`
tools::requires() {
  local requirements=(
    "grep"
    "cat"
    "sed"
  )
  if [ "${1}" == "darwin" ]; then
    requirements+=("brew")
  fi
  echo "${requirements[@]}"
}

# call it as
# result=( $(tools::read_global_deps <file_name>) )
tools::read_global_deps() {
  local deps=()
  while IFS= read -r line; do
    deps+=("${line%'::'*}")
  done < <(cat "$1" | grep -v '\#')
  echo "${deps[@]}"
}

tools::hint() {
  line=$(cat "$1" | grep "^$2::*")
  echo "${line#*'::'}"
}

tools::has() {
  case "$1" in
  brew:*)
    tools::in_brew "${1#"brew:"}"
    return
    ;;
  pkgutil:*)
    tools::in_pkgutil "${1#"pkgutil:"}"
    return
    ;;
  type:*)
    type "${1#"type:"}" &>/dev/null
    return
    ;;
  *)
    type "$1" &>/dev/null
    return
    ;;
  esac
}

tools::in_brew() {
  brew list | grep -Fx "$1" >>/dev/null 2>&1
}

tools::in_pkgutil() {
  pkgutil --pkg-info="$1" >>/dev/null 2>&1
}

# This should be called as
# tools=("brew" "coreutils")
# missing=( $(tools::missing "${tools[@]}") )
tools::missing() {
  local tools=("$@")
  local missing=()
  for tool in "${tools[@]}"; do
    if ! tools::has ${tool}; then missing+=(${tool}); fi
  done
  echo ${missing[@]+"${missing[@]}"}
}

# This should be called as
# requirements=($(console::requires) $(tools::requires) "awk")
# $(tools::verify "${requirements[@]}")
function tools::verify() {
  local requirements=("$@")
  (console::info "checking requirements: '${requirements[@]}' ... ")
  local missing=$(tools::missing "${requirements[@]}")
  if [ -n "${missing}" ]; then
    echo ""
    (console::errorLn "The following mandatory commands are not available\n ${missing}")
    exit 1
  fi
  (console::infoLn "OK")

  local tools=($(tools::read_global_deps ${SELF_DIR}/../.global-deps))
  (console::info "checking tools: '${tools[@]}' ... ")
  local missing=$(tools::missing "${tools[@]}")
  if [ -n "${missing}" ]; then
    (
      echo ""
      (console::errorLn "Run the following commands to install missing dependencies")
      for tool in "${missing[@]}"; do
        hint=$(tools::hint "${SELF_DIR}/../.global-deps" "${tool}")
        console::warn "    $(console::red)${hint}"
      done
      echo ""
    )
    exit 1
  fi
  (console::infoLn "OK")
}

function tools::bootstrap() {
  local shell=${1}
  (console::infoLn "detected shell '${shell}'")
  case "${shell}" in
  bash)
    local config_file=~/.bash_profile
    ;;
  fish)
    local config_file=~/.config/fish/config.fish
    ;;
  zsh)
    local config_file=${ZDOTDIR:-~}/.zshrc
    ;;
  *)
    console::warnLn "Unsupported shell ${shell}. You are on your own."
    exit 1
    ;;
  esac
  if [ $? -eq 0 ]; then
    (console::infoLn "OK")
    (console::infoLn "Congratulations!!! Your system is ready.")
  fi
}

if [[ "$(basename -- "$0")" == "tools.sh" ]]; then
  echo "Don't run $0, source it" >&2
  exit 1
fi
