# Changelog

## Unreleased

- Replace the sound player with a native low-latency engine: a C++17 mixer on top of Oboe 1.11.0 with a 128-voice pool, per-voice gain ramps, a fractional interaural time delay, a head shadow and pitch-preserving rate changes. A voice now carries a position (left/right, distance, front/back), so a projectile really travels in from far away to nearby and back, and every interaction is heard on the next mixing block. If the library or the stream cannot start, the game stays playable in silence instead of crashing.
- Greet the player on launch, once per process: the title, who made it, and what to do next, in the chosen language.
- Stop the car from braking in a loop: a parked car tolerates a one-metre gap, so a standing enemy cannot make it start and brake over and over, and the brake is heard once.
- Make the electric shock dependable: one authority decides whether it is burning, so a hold that is refused because a projectile is in the air is silent and harmless, and the hum, the damage and the barrier break can no longer disagree. A refused swipe says why.
- Stop Robert from firing from bands where nothing can land: he closes the distance first, his shots use the weapon he is really holding (so a magazine is never spent on another gun), and a miss is audible - the miss sound, or the shot itself at the edge of the range. The AWM keeps its long-range precision band.
- Make the three-finger shortcuts land the first time: the swipe needs a deliberate travel above the tap slop and a clearly dominant axis, so a sloppy diagonal can no longer turn a pause into an exit.

## 1.2 Beta

- Award coins by the capability and power of the whole battle instead of the number of plays: every landed weapon pays its own power points (missile 600, atom bomb 500, grenade and timer bomb 350, guns scaled from their heavy-zone damage), one real electric shock pays 700 to 1000 coins on its own, and surviving health plus true accuracy add their share. A win pays up to 2500 coins and the result still names the shock share separately.
- Rebalance the sound levels: the machine gun, submachine gun, grenade and atom bomb are played at exactly the speech level, the music is the only bus trimmed under it, the settings sliders scale every sound live, and no recording is ever pitch shifted. Only the footstep rate follows the walking speed, inside a narrow 0.8 to 1.25 natural window, with a smooth four-point interpolation curve so a rate change is transparent.
- Make both interceptors pre-armable: the anti-missile system and the anti-bomb explosive can be deployed with nothing in the air, and an armed defence then destroys the next projectile by itself. One bought anti-missile system covers three missiles, one enemy battery covers one, and Robert fires a stored interceptor at any missile that is really in the air.
- Add "Lock the Opponent" between Cover and Exit Game: one minute of complete freeze for a wallet price of 1500 coins. A locked Robert cannot move, fire, throw a bomb, launch a missile or use a defence, no offensive weapon can be used on him either, every defence stays usable, and a second click never extends the lock.
- Play the car brake exactly once: the brake is a single one-shot sound that is never chained into a sequence and never restarted on top of itself.
- Fix the electric shock: it works only between 40 and 100 percent proximity (outside the band the swipe is refused with a click), the enemy answers at random either with his own shock - both fighters then lose 10 percent of their maximum health every two seconds - or by fleeing out of the band, and a duel drain is symmetric.
- Bar the electric shock and every gun while a missile or a bomb is in the air: the weapon is held aiming at the opponent and then drifts back, which is the grace time to deploy a defence.
- Remove the remaining half-second of playback latency: no sound is held back, queued or truncated before it starts, and every interaction is heard on the next mixing block.
- Drop the bulletproof car price to 1000 coins, add the lock item to the guide and the shop, and update the guide in all three languages. The app is now OpenFire Beta version 1.2 Beta (version code 14).

## 1.1 Beta

- Rebuild the sound player around a new low-latency mixer: every interaction - gun switch, reload, fire, menu move, door, car - plays and stops with immediate audio, with no one-second delay and no freezing.
- Keep every sound at its natural pitch: no theme or rate pitch shifts anywhere. Only the walking rhythm speeds up when the player runs and slows down again.
- Give enemies real distance volume: Robert grows louder as he approaches and quieter as he moves away, and footsteps gain the same distance effect.
- Replace the bulletproof car with the fully automatic model: choose Car, open the door, close it, and the car drives itself toward the enemy while the player keeps swiping and firing under its cover. Exit Car brakes and walks the player out through the door prompts, the same car is reused from the menu, it has five uses and a 500-coin refuel, and one missile or two consecutive bomb hits destroy it unless the anti-missile system or anti-bomb explosive neutralises them. Robert takes the wheel of his own car from round five when his health is below 400 percent and answers an occupied car with missiles and bombs.
- Redesign the electric shock: 80 percent per second at 40 to 100 percent proximity, and when both fighters shock at once the sounds mix together and the first activator drains 10 percent per second while the second drains 10 percent per three seconds. Ten sustained seconds inside the 90 to 100 band break a glue wall or a cover. Robert detects and uses the shock on his own and can counter the player's shock.
- Award coins by firing power instead of shot counts: powerful weapons and hard hits earn more than sprays of light bullets, up to 1500 coins per game. The result announces the coins received, an active loan deduction only when a loan is really open, then the new level or round completion - and nothing else.
- Raise speech 25 percent above effects and music so every announcement is clear; footsteps play at the speech level.
- Redesign the interface with a fresh look and clearer menus. The app is now OpenFire Beta, version 1.1 Beta (version code 13), and every guide and changelog reflects it.

## 2.8

- Remove the enemy's pre-range gunfire: Robert no longer fires a feint or any shot from the deception band to make the player think he is about to shoot, and the feint logic is deleted. He fires only when he is in his real firing range.
- Add the AR-15 rifle: a player firearm from round one, with its own `ar-15` sound. Robert never carries it.
- Add the bulletproof car (defence menu, between the anti-missile and the anti-bomb). It costs 3,500 coins, is unlocked after round one and can be used in two battles before it needs a refuel for 2,000 coins. Inside the closed car no bullet reaches the user, and the user can still fire from inside. Only missiles and bombs can destroy it, and an active anti-missile or anti-bomb system blocks them. Robert shoots normally at the car until its health drops below 300, then he uses his missiles and bombs against it. Its door, start, drive, brake, fire, reload, refuel and exit gestures follow the spoken prompts, with the car start, engine, moving, brake, door and crash sounds used for each step.
- Add the electric shock, unlocked after round two and sold for 1,500 coins with the machine guns. Holding the up-swipe starts the magical sound at once and drains Robert's health; it is capped at 30 seconds per use. Robert can answer only after five seconds of the user's shock, drains 10% of the user's health per second and stops the moment the user lets go. Releasing the shock plays the electric-shock sound mixed with the enemy-hit sound.
- Rework the audio mixer: every sound is decoded once and mixed on one output stream, so thousands of sounds can play at the same time without freezing one sound on another. A chained sound (such as the car start followed by the engine) starts on the exact frame the previous one ends.
- Convert every sound asset from MP3 to OGG and rename all of them to match the code that uses them. Unused river and water-walk sounds are removed.
- Fix the shop's TalkBack focus: shop rows are keyed, so scrolling no longer moves the focus to another item.
- Let the shotgun and the assault rifle be disabled from the shop, but never removed or sold. At least one gun must stay enabled.
- Remove the "Max Speed" extension and all of its logic.
- Bump to version 2.8 (version code 12).

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
