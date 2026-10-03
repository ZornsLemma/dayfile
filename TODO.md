Is the history compactor design too prefix-y? If you type something "at the start", every keystroke is a new history entry. Could/should we make it more "substring-y"? That may be hard or not really worth it, and there is some sense in which adding new stuff at the end is the "main" case, and a bit of noise in the history isn't the end of the world. Just something to think about.

Follow-up while reviewing the history screen's scroll logic: the mid-entry newline case is maybe the particularly interesting one here - inserting a line in the middle or at the start of an entry defeats the prefix compactor entirely, so a burst of typing there produces one history row per debounce window (it's the 400ms debounce that bounds the noise, not literally every keystroke). If we do pursue a smarter algorithm after real-world experience, it might want to treat newlines specially and work in a vaguely diff-like way at line granularity - e.g. if an entry has three lines and the user changes line 2, the history entry shown to the user has lines 1 and 3 elided. Very rough top-of-head thoughts. Any such algorithm would only reduce row counts further, so the history screen's scroll-to-top mechanism is agnostic to the swap.

Some sort of search feature?

CategoryDao.observeAllEnabledCategories() is dead - implemented by three test fakes, called from main nowhere. Left alone to keep this release diff small. ui/theme/Type.kt's bodyLarge override is also currently identical to the Material 3 default and therefore does nothing; commented to say so rather than deleted, since I'd rather not assert an equivalence I can't compile-check.

Would it be possible to set up a github action so that when a release is uploaded, a build is done in the same way F-Droid would and the uploaded binary is checked against the github-built one? github cannot sign the binary it builds - only I have the signing key - but just as F-Droid does it could check the binary is otherwise identical. This might be a bit tricky, as as soon as the release is tagged F-Droid/Obtainium might pick it up, so the tag is kind of burned and if the reproduction fails it is "too late". Albeit I still at least discover it earlier than waiting for F-Droid's build.


The instrumented tests were failing on GitHub CI while passing on my desktop. Not app bugs: the suite has real synchronisation gaps (it assumed `waitForIdle()` synchronises Room, DataStore and HomeViewModel's edit channel, none of which it does), and it also assumes the merged semantics tree is current when an assertion runs. On a runner with no GPU every frame is CPU-rendered by SwiftShader, which left those windows wide enough to hit repeatedly. Several were fixed, and each fix exposed the next, which is the signal: ~112 tests that drive a full UI are expensive to make deterministic on a slow software renderer.

What I know about the cause, so this needn't be rediscovered:

- The emulator is the variable, not the code. 1440x3120 (pixel_7_pro) is 4.5 megapixels of software rendering on 2 cores, versus ~1 megapixel on my desktop. CI resolved emulator 37.2.12.0 where my SDK had 36.2.12.0, and that difference alone was enough to flip tests between passing and failing on identical settings. So "passes locally" never meant the same thing as "passes on CI".
- Mitigated for now by running the workflow on `pixel_2` (2.1 megapixels, same 411dp width so horizontal layout is unchanged) rather than `pixel_7_pro`. That is a mitigation, not a fix, and the note in release-check.yml says so.
- Fixes that did land and should be kept: `awaitRenderedNode` in `BaseAppTest`, the self-synchronising assertion helpers in `BaseHomeScreenTest`/`BaseHistoryScreenTest`/`BaseCategoryScreenTest`, and the waits in `launchHomeScreen`/`launchHistoryScreen`. These are real latent races and are worth having whether or not the emulator ever goes back to a big profile.
- The fix I tried and reverted: giving `HistoryViewModel.uiStateFlow` a `stateIn(..., null)` initial and having `HistoryScreen` render nothing until state arrives. Reasoning is sound (it removes the placeholder that flashes "nothing recorded" on every open, and makes the two screens consistent with `HomeViewModel`), but it broke the slide-in transition on the history screen and I did not want to ship an app-behaviour change on that basis without proper retesting. If revisited, start there.

Worth knowing before spending more time on it: not one of the 112 instrumented tests launches `MainActivity` or the `NavHost`. They are `createComposeRule()` + `setContent` composable tests. So navigation, activity lifecycle and state restoration are untested by this suite regardless, which is a large part of why its cost-to-value ratio is poor. If instrumented coverage is wanted later, testing the real activity is probably a better use of the time than stabilising composable-in-isolation tests.

=== Unpinned behaviour that a reader of the tests alone would not know exists (September 2026)

- The MainActivity side of the retained-home session reset. `HomeScreenSessionResetTest` drives
  `homeSessionResetToken` directly, so the `else` branch in MainActivity that increments it - the
  one that fires only when the current destination is already "home" - has no coverage. A mistake
  there (e.g. incrementing on the short-return path too) would pass every test. See
  `docs/HOME_SCREEN_NOTES.md` §7.7.
- `HomeViewModelTeardownTest` pins the observable half of the edit pipeline's teardown handling
  (input after the scope is cancelled is dropped without crashing), but it cannot observe the
  `finally { userEdits.close() }` in the consumer. Without that close, the mid-lifetime
  `check(trySend(...))` silently stops being able to detect a dead writer - the original bug - and
  no test would notice. Both are deliberate behaviours rather than defects, and both are now
  written up in docs/HOME_SCREEN_NOTES.md §2.1.
