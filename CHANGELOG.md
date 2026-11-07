# Changelog

## 2.6

- Remove the phantom weapon loops: the submachine gun, the AK-47 and the machine gun no longer leave a firing loop running when nobody is holding the trigger. A looping player now exists only while that weapon is really held, is released when the battle screen closes, and each loop has its own channel so it can never be started, restarted or masked by another weapon.
- Limit Robert's fake shots to single-shot weapons. A feint from the deception band now uses the M10, the pistol or the sniper only, never a continuous-fire weapon whose burst recording made one fake shot sound like a real attack.
- Rework the audio pipeline so sounds stop being cut off: every `MediaPlayer` is prepared through a single serialised slot with a bounded wait, failed preparations are dropped instead of released mid-play, and a track that is still preparing can no longer be started late or leak.
- Load the speech language and the speech voice independently in Settings, populate the voice list for the selected voice language and save both. Applying an unchanged voice configuration no longer flushes the sentence that is being spoken.
- Raise the damage ceiling from 60 to 80 health points and rebuild the whole table as a power ladder: light hits take half of a weapon's point-blank power, strong hits 80 percent and heavy hits the full power. The submachine gun takes 26 HP point-blank, the AWM 80 HP, and every weapon in between follows its own power level.
- Start continuous fire instantly on the hold, keep it gapless, and let no other sound interrupt it: the mixer now runs a weapon bus that sits below the impact bus, so a hit marker, a footstep or an explosion is always louder than the gun that caused it.
- Balance the effects: player and enemy hit sounds play at full level while weapon fire is trimmed, so the feedback of a landed shot is never buried under gunfire.
- Fix the volume mixer: the music slider is the real output level again (no hidden background gain), and every speech, music and effects level is applied once and saved.
- Add a Voice guidance setting: with it off the in-game feedback of the drop, flight and battle screens is muted while every menu, the guide, the shop and the result screen keep speaking.
- Fix the three-finger gestures: vertical swipes (down to pause or resume, up to leave) now work exactly like the horizontal ones. Three-finger gestures are dispatched the moment they are recognised, using the average movement of all fingers, instead of waiting for every finger to lift.
- Add a separate Voice language dropdown, a voice list that always belongs to that language, and a missing-voice message instead of an empty list.
- Rewrite the Hindi and Nepali interface texts and guides so they match the English rules, the new damage table, the new gestures and the new settings.
- Bump to version 2.6 (version code 10).

## 2.5

- Mix concurrent sound effects independently, including repeated instances of the same weapon or item.
- Add interest-free shop loans of 500, 1000 or 1500 coins for players with fewer than 500 coins and no outstanding balance. Each win automatically repays the debt from gross winnings; the result reports the deduction, remaining balance and net coins.
- Pause and resume battles with a three-finger left swipe. Movement, shots, projectile countdowns, the match timer and active audio freeze until play resumes. A three-finger right swipe cycles running speed.
- Reduce and reorder shop prices, cap every individual attack at 60 health points and make smaller weapons more effective. Bombs and missiles track their targets but no longer guarantee a kill.
- Trigger faster retreat on heavy or sustained incoming damage; space single shots by 1.5 seconds and continuous shots by 1 second.
- Refresh the complete English, Hindi and Nepali guides and accessible shop/result announcements.

Historical in-app release notes for 1.0–2.4 remain available under Changelog.
