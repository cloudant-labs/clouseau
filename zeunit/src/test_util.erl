-module(test_util).
-export([setup/0, teardown/1]).

-include("zeunit.hrl").

setup() ->
    {ok, _} = net_kernel:start(?NodeT, #{name_domain => longnames}),
    true = erlang:set_cookie(?NodeT, ?COOKIE),
    pong = util:check(?NodeZ).

teardown(_) ->
    ok = net_kernel:stop().
