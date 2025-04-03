#!/bin/bash
SELF_DIR="$(realpath "$(dirname "${BASH_SOURCE[0]}")")"
TMP_DIR=${SELF_DIR}/../tmp
ZEUNIT_DIR=${SELF_DIR}/../zeunit
STOP_TIMEOUT_SEC=60

: "${REBAR:=rebar3}"

mkdir -p "$TMP_DIR"
. "${SELF_DIR}"/console.sh

run::get_hash() {
  echo "$1" | sha256sum | cut -d " " -f 1
}

run::start() {
  local id=$1
  local hash=$(run::get_hash "$id")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ -f "$pid_file" ] && console::errorLn "PID file already exist!" && return 1

  shift
  console::infoLn "Starting \"${id}\" using \"${@}\" ...."
  $@ &
  echo $! >"$pid_file"
}

run::stop() {
  local hash
  hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ ! -f "$pid_file" ] && console::errorLn "Not found PID file!" && exit 1

  pkill -F "$pid_file" && console::infoLn "Stopping \"${1}\"...."
  for i in $(seq 1 ${STOP_TIMEOUT_SEC}); do \
    printf ">>>>>> Waiting... (%d seconds left)\n" $(expr ${STOP_TIMEOUT_SEC} - $i); \
    sleep 1; \
    pid=$(cat "$pid_file"); \
    if ! pgrep -F "$pid_file" ; then \
      echo ">>>>>> \"${1}\" stopped"; \
      rm -f "$pid_file"; \
      break; \
    fi; \
  done
}

run::java_thread_dump() {
  local hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ ! -f "$pid_file" ] && console::errorLn "Not found PID file!" && exit 1
  jstack "$(cat "${pid_file}")"
}

run::health-check() {
  [ -z "$2" ] && escript "${ZEUNIT_DIR}/src/health-check.escript" "-name" "$1" ||
  escript "${ZEUNIT_DIR}/src/health-check.escript" "-name" "$1" "-setcookie" "$2"
}

run::eunit() {
  cd "${ZEUNIT_DIR}" && $REBAR eunit "$@"
}
