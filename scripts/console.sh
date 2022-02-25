#!/bin/bash
# call it as
# result=( $(console::requires) )
console::requires() {
    local requirements=(
        "tput"
    )
    echo "${requirements[@]}"
}

console::bold() {
    echo $(tput bold)
}

console::red() {
    echo $(tput setaf 1)
}

console::green() {
    echo $(tput setaf 2)
}

console::yellow() {
    echo $(tput setaf 3)
}

console::reset() {
    echo $(tput sgr0)
}

console::init() {
    echo "OK"
}

console::info() {
    echo -n -e "$(console::bold)${*}$(console::reset)"
}

console::infoLn() {
    echo -e "$(console::bold)${*}$(console::reset)"
}

console::warn() {
    echo -n -e "$(console::yellow)${*}$(console::reset)" >&2
}

console::warnLn() {
    echo -e "$(console::yellow)${*}$(console::reset)" >&2
}

console::error() {
    echo -n -e "$(console::red)${*}$(console::reset)" >&2
}

console::errorLn() {
    echo -e "$(console::red)${*}$(console::reset)" >&2
}

# call as follows
# (console::debugLn $DEBUG_FLAG "hello debug message")
console::debugLn() {
    [[ $1 ]] && echo -e "${@:2}"
}

# call as follows
# (console::debug $DEBUG_FLAG "hello debug message")
console::debug() {
    [[ $1 ]] && echo -n -e "${@:2}"
}

# call it as follows
# markdown help_message
# markdown "help_topic $1"
# Where help_message is
# function help_message() {
#    cat <<"EOF"
# EOF
# }
function console::markdown() {
    if tools::has bat ; then
        $1 | bat --language md --style=plain --color always
    else
        $1
    fi
}


if [[ "$(basename -- "$0")" == "console.sh" ]]; then
    echo "Don't run $0, source it" >&2
    exit 1
fi
