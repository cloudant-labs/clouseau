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

  local ts=$(date -u +"%Y-%m-%dT%TZ")
  local log_file="${TMP_DIR}/${id}.${ts}.log"

  shift
  console::infoLn "Starting \"${id}\" using \"${@}\" ...."
  $@ > ${log_file} 2>&1 &
  echo $! >"$pid_file"
  run::print_log "${id}"
}

run::last_log() {
  ls -t1 "${TMP_DIR}/" | grep "${1}.*.log" | head -n 1 | xargs -I {} echo "${TMP_DIR}/{}"
}

run::print_log() {
  local log_file=$(run::last_log "${1}")
  [ ! -f "$log_file" ] && console::errorLn "Not found log file!" && exit 1
  console::infoLn "The process logs are written to ${log_file}"
}

run::stop() {
  local hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ ! -f "$pid_file" ] && console::errorLn "Not found PID file!" && exit 1

  pkill -F "$pid_file" && console::infoLn "Stopping \"${1}\"...."
  for i in $(seq 1 ${STOP_TIMEOUT_SEC}); do \
    printf ">>>>>> Waiting... (%d seconds left)\n" $(expr ${STOP_TIMEOUT_SEC} - $i); \
    sleep 1; \
    pid=$(cat "$pid_file"); \
    if ! pgrep -F "$pid_file" >/dev/null 2>&1; then \
      echo ">>>>>> \"${1}\" stopped"; \
      rm -f "$pid_file"; \
      break; \
    fi; \
  done
  run::print_log "$1"
}

run::java_thread_dump() {
  local hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  [ ! -f "$pid_file" ] && console::errorLn "Not found PID file!" && exit 1

  if [ $# -eq 1 ]; then
    jstack "$(cat "${pid_file}")"
  else
    jstack "$(cat "${pid_file}")" > $2
  fi
}

run::processId() {
  local hash
  hash=$(run::get_hash "$1")
  local pid_file="${TMP_DIR}/${hash}.pid"

  if [ -f "$pid_file" ]; then
    cat "$pid_file"
  else
    if jcmd | grep -F clouseau | cut -d' ' -f1 >temp && [ -s temp ]; then
      cat temp
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
