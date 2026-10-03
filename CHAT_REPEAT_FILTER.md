# Chat Repeat Filter

SunnyMod collapses consecutive identical incoming chat messages into one visible line.

Example:

```text
You unearthed something buried in the ground! (1x)
```

The `(1x)` means one repeat after the initial message. Further identical messages update the same line to `(2x)`, `(3x)`, `(100x)`, etc.

A new/different chat message starts a new entry. The same message starts a new burst if it has been quiet for more than 30 seconds.

The filter currently applies to incoming player/chat messages exposed through Fabric's `ClientReceiveMessageEvents.ALLOW_CHAT` event. It does not change messages sent to the server.

Command:

```text
/sunnymod chatrepeat reset
```

This resets the current repeat state without clearing normal chat history.
