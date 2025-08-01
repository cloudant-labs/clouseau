-module(config).

-export([
    get/1,
    get/2,
    set/2
]).

-define(KEY(Key), {?MODULE, Key}).

get(Key) ->
    persistent_term:get(?KEY(Key), undefined).

get(Key, Default) ->
    persistent_term:get(?KEY(Key), Default).

set(Key, Value) ->
    persistent_term:put(?KEY(Key), Value).
