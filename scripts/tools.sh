#!/bin/bash
SELF_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

BIN_DIR=${SELF_DIR}/../bin

. ${SELF_DIR}/console.sh

# call it as
# result=( $(tools::requires) )
tools::requires() {
    local requirements=(
        "grep"
        "cat"
        "sed"
        "brew"
    )
    echo "${requirements[@]}"
}

# call it as
# result=( $(tools::read_global_deps <file_name>) )
tools::read_global_deps() {
    local deps=()
    while IFS= read -r line; do
        deps+=(${line%'::'*})
    done < <(cat "$1" | grep -v '\#')
    echo "${deps[@]}"
}

tools::hint() {
    line=$(cat $1 | grep "^$2::*")
    echo "${line#*'::'}"
}

tools::has() {
    case "$1" in
        brew:*)
            tools::in_brew ${1#"brew:"}
            return
            ;;
        pkgutil:*)
            tools::in_pkgutil ${1#"pkgutil:"}
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
# misssing=( $(tools::missing "${tools[@]}") )
tools::missing() {
    local tools=("$@")
    local missing=()
    for tool in ${tools[@]}; do
        if ! tools::has ${tool} ; then missing+=(${tool}); fi
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

    local tools=( $(tools::read_global_deps ${SELF_DIR}/../.global-deps) )
    (console::info "checking tools: '${tools[@]}' ... ")
    local missing=$(tools::missing "${tools[@]}")
    if [ -n "${missing}" ]; then
        (
            echo ""
            (console::errorLn "Run the following commands to install missing dependencies")
            for tool in "${missing[@]}"; do
                hint=$(tools::hint "${SELF_DIR}/../.deps" "${tool}")
                console::warn "    $(console::red)${hint}"
            done
            echo ""
        )
        exit 1
    fi
    (console::infoLn "OK")
}

function tools::bootstrap_topic() {
    cat ${SELF_DIR}/bootstrap.md | awk "/##.*\`${1}\`:.*/,/---/ {print}" -
}

function tools::bootstrap() {
    local shell=${1}
    (console::info "detected shell '${shell}'")
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
    (console::info "checking asdf setup ... ")
    if ! cat ${config_file} 2>/dev/null | grep 'asdf.[f]*[i]*sh' >>/dev/null 2>&1 ; then
        console::warnLn "Action required!!!"
        console::markdown "tools::bootstrap_topic ${shell}-asdf"
        exit 1
    fi
    (console::infoLn "OK")
    (console::info "checking direnv setup ... ")
    if ! asdf current direnv >>/dev/null 2>&1; then
        console::warnLn "Action required!!!"
        console::markdown "tools::bootstrap_topic ${shell}-direnv"
        exit 1
    fi
    if ! cat ~/.config/direnv/direnvrc 2>/dev/null | grep 'asdf direnv hook asdf' >>/dev/null 2>&1 ; then
        console::warnLn "Action required!!!"
        console::markdown "tools::bootstrap_topic direnv"
        exit 1
    fi
    (console::infoLn "OK")
    (console::infoLn "Congratulations!!! Your system is ready.")
}

function tools::deps() {
    local class="${1}"
    class=${class} \
        yq '.[env(class)][] | key' deps.yaml
}

function tools::deps_classes() {
    yq '. | keys' deps.yaml
}

function tools::get_dep() {
    local class=""
    local tool=""
    IFS=":" read class tool <<< "${1}"
    local field="${2}"
    class=${class} tool=${tool} field=${field} \
        yq '.[env(class)].[env(tool)].[env(field)]' deps.yaml
}

function tools::has_version() {
    local binary="${1}"
    local version="${2}"
    [ -x "${BIN_DIR}/${binary}-${version}" ]
}

function tools::activate_tools() {
    local class=""
    local tool=""
    local version=""
    for class in $(tools::deps_classes); do
        for tool in $(tools::deps "${class}"); do
            version=$(tools::get_dep "${class}:${tool}" "version")
            if ! $(tools::has_version "${tool}" "${version}"); then
                tools::install_tool "${class}:${tool}"
            else
                (console::infoLn "${tool} ${version} is already installed")
            fi
            tools::activate_tool "${class}:${tool}"
        done
    done
}

function tools::activate_tool() {
    local class=""
    local tool=""
    IFS=":" read class binary <<< "${1}"
    [ -L "${BIN_DIR}/${binary}" ] && rm "${BIN_DIR}/${binary}"
    (console::infoLn "activating '${class}:${binary}' ${version}")
    ln -s "${BIN_DIR}/${binary}-${version}" "${BIN_DIR}/${binary}"
}

function tools::install_with_coursier() {
    local class=""
    local tool=""
    IFS=":" read class binary <<< "${1}"
    local version=$(tools::get_dep "${1}" "version")
    local install_args=$(tools::get_dep "${1}" "install_args")
    local id=$(tools::get_dep "${1}" "id")
    (console::infoLn "installing '${1}'... ")
    status=$(coursier bootstrap ${id}:${version} \
        ${install_args} --standalone -o "${BIN_DIR}/${binary}-${version}")
    if [[ $status -eq 0 ]]; then
        (console::infoLn "'${1}' is installed")
    else
        (console::infoLn "there was a failure while installing '${1}'")
        exit 1
    fi
}

function tools::install_tool() {
    local class=""
    local tool=""
    IFS=":" read class _binary <<< "${1}"
    case "${class}" in
        coursier)
            tools::install_with_coursier "${1}" || exit $?
            ;;
        *)
            console::warnLn "Unsupported tool class '${class}'"
            exit 1
            ;;
    esac
}

if [[ "$(basename -- "$0")" == "tools.sh" ]]; then
    echo "Don't run $0, source it" >&2
    exit 1
fi
