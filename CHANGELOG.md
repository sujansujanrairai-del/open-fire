# Changelog

## 2.7

- Remove the MP40 from the game completely: its weapon entry, its damage row, its shop slot and free issue, its sound asset, its sound alias, its synthetic fallback, its mixer bus key, its interface text in all three languages and every test that named it are gone. No fighter can own, buy, be issued or be shot with it any more.
- Keep every continuous-fire weapon away from Robert. Rounds one to five never give him a machine gun, an AK-47 or a submachine gun — not from his round loadout, not from the pistol mirror, not from a reward and not from a feint. A single `RobertRules.isEnemyAllowed` gate is applied where the loadout is built and again where the firing pool is chosen, so no path can arm him with one, and every bullet he fires is a single-shot round.
- Let a continuous machine-gun burst play to the end: the held loop now runs on its own pooled stream above every effect, the one-shot effects are held inside their own stream budget so they can never take the loop's stream, and a watchdog re-arms the loop on every bullet if anything does take it away. An enemy hit, a footstep or a blast is mixed on top of the burst instead of cutting it.
- Let an enemy under continuous automatic fire escape. Two landed bullets of one continuing burst now break contact through `RobertRules.shouldFleeUnderSustainedAutomaticFire`, evaluated before the exchange rules, so Robert's own answering shots — and an active cover, glue wall or medkit — can no longer pin him in the stream.
- Add an owner-aware `AudioManager.stopWeaponSound`, so a stop of one fighter's weapon can never silence the other fighter's held burst.
- Bump to version 2.7 (version code 11).

## 2.6

- Remove the phantom weapon loops for good: the submachine gun, the AK-47 and the machine gun can no longer fire on their own. A firing loop now exists only while one fighter is really firing that weapon - it is created by the hold and destroyed by the release, so no pause, resume, watchdog, weapon switch or late preparation can ever bring a burst back, and unpausing a battle only restarts a sound whose owner still asks for it. Each fighter owns his own channel per weapon, so the player's finger and Robert's answer can never start, restart, mask or mute each other.
- Fire the enemy's automatic weapons like the player's: an enemy burst lasts exactly as long as his firing and is cut the moment he switches weapon, throws a bomb, starts running or stops answering. Before this, one enemy AK-47 shot played its whole three-second recording, so several of those overlapping bursts were heard as one endless, disturbing burst that kept roaring after both fighters had gone quiet. A single shot of an automatic weapon - the player's tap included - is now one bounded burst of one beat instead of the whole recording, and any other request for one of those three recordings is cut after at most 0.9 seconds, so gunfire can never outlive the firing that caused it.
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
