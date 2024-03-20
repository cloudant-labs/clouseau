#!/bin/bash
SELF_DIR="$(realpath "$(dirname "${BASH_SOURCE[0]}")")"
TMP_DIR=${SELF_DIR}/../tmp
ZEUNIT_DIR=${SELF_DIR}/../zeunit
: "${REBAR:=rebar}"

mkdir -p "$TMP_DIR"
. "${SELF_DIR}"/console.sh

run::get_hash() {
  echo "$1" | sha256sum | cut -d " " -f 1
}

run::start() {
  local hash
  hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ -f "$pid_file" ] && console::errorLn "PID file already exist!" && return 1

  shift
  $@ &
  echo $! >"$pid_file"
}

run::stop() {
  local hash
  hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ ! -f "$pid_file" ] && console::errorLn "Not found PID file!" && exit 1

  pkill -F "$pid_file"
  rm -f "$pid_file"
}

run::health-check() {
  escript "${ZEUNIT_DIR}/src/health-check.escript" "$1"
}

run::eunit() {
  cd "${ZEUNIT_DIR}" && $REBAR eunit "$@"
}
