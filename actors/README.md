# zio-actors

We intend to use this work as a minimalistic base to create our own actors library.

Therefore we took the code from https://github.com/zio/zio-actors as of commit
[1560ff2d2cc000cf36313b5ebf659467f5a5a191](https://github.com/zio/zio-actors/commit/1560ff2d2cc000cf36313b5ebf659467f5a5a191).

We removed functionality related to remote actors and upgraded to ZIO 2.0.0-RC2.
The result of our changes can be seen in

[0001-migrate-to-ZIO-2.0.0-and-remove-remote-actors.patch](./0001-migrate-to-ZIO-2.0.0-and-remove-remote-actors.patch)

Then we committed the content of modified source directly into ziose repository.
