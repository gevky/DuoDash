# Display profiles

DuoDash uses one dashboard layout across displays. The four dual-screen bars are temporarily hidden while no external display is connected; their saved order and visibility are unchanged. Each output separately remembers its name, display mode, resolution, aspect ratio, brightness, audio, handheld screen-off preference and dashboard location.

## Use external screen

A TV or glasses show the normal Android session, including home, Settings and apps. Android's main display remains the session source. Its logical resolution changes to the selected shape, so the handheld may show black borders or have its screen turned off. Physical HDMI/DisplayPort timing is separate from this logical resolution.

## Use both screens

The handheld and add-on run separate apps. Leaving external-screen mode restores the previous main-display resolution override. Independent display resolution choices remain available.

## Show DuoDash on

Each output remembers Handheld or External. Disconnecting falls back to the handheld without overwriting that choice. Setup and pairing stay on the handheld.

In external-screen mode, a dashboard shown on External is part of the mirrored main session and also appears on the handheld while its screen is on. In dual-screen mode, it opens on the separate external display. A saved dim or black-screen setting is temporarily lifted while the external dashboard is open.

## Safeguards and limits

Resolution changes use a timed Keep/Revert preview. Profiles use stable output identities rather than temporary Android display numbers. The main-display override is saved before docking and restored when the session ends; a device-side watcher also handles helper loss.

Visible apps on an independent external display can block a switch to mirrored mode. DuoDash does not silently move or terminate them. Changing resolution or moving an emulator can restart it.

Returning controller focus to an external app is not reliable on all tested paths. Physical reconnect and reboot recovery still need broader testing. Retroid backlight controls also depend on the device firmware.
