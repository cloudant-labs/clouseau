#!/bin/bash
SELF_DIR="$(realpath "$(dirname "${BASH_SOURCE[0]}")")"
TMP_DIR=${SELF_DIR}/../tmp
ZEUNIT_DIR=${SELF_DIR}/../zeunit
: "${REBAR:=rebar3}"

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

  pkill -F "$pid_file" && console::infoLn "Stopping \"${1}\"...."
  rm -f "$pid_file"
}

run::processId() {
  local hash
  hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  if [ -f "$pid_file" ]; then
    cat "$pid_file" > "${TMP_DIR}/$2.pid"
  else
    if jcmd | grep -F clouseau | cut -d' ' -f1 >temp && [ -s temp ]; then
      cat temp > "${TMP_DIR}/$2.pid"
      rm -f temp
    else
      rm -f temp
      console::errorLn "Can't find the running node: $1!" && return 1
    fi
  fi
}

run::health-check() {
  [ -z "$2" ] && escript "${ZEUNIT_DIR}/src/health-check.escript" "-name" "$1" ||
  escript "${ZEUNIT_DIR}/src/health-check.escript" "-name" "$1" "-setcookie" "$2"
}

run::eunit() {
  cd "${ZEUNIT_DIR}" && $REBAR eunit "$@"
}
