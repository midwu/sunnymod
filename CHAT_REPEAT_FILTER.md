# Chat Repeat Filter

SunnyMod collapses consecutive identical messages that reach the normal Minecraft `ChatHud`, including server/system messages such as:

`You unearthed something buried in the ground!`

The first message is shown normally. Each subsequent identical message within 30 seconds replaces the newest chat entry with the same message plus a counter:

- first: `You unearthed something buried in the ground!`
- first repeat: `You unearthed something buried in the ground! (1x)`
- fifth repeat: `You unearthed something buried in the ground! (5x)`
- hundredth repeat: `You unearthed something buried in the ground! (100x)`

No additional chat line is added for the repeats.

The filter works at the actual `ChatHud.addMessage(Text)` insertion point instead of the player-chat event, because server/system messages are delivered through a different path.

Use `/sunnymod chatrepeat reset` to clear the current repeat state.
