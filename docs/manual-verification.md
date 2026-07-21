# Manual verification

Items here **cannot** be exercised headlessly and are unchecked until run manually against each
IDE version the plugin targets. Re-run them when the code under each item changes.

## Status bar and seal — platform-resolved paths

Both items below depend on services a `HeavyPlatformTestCase` does not provide (a real `IdeFrame`;
a real plugin class loader), so CI cannot cover them. Their headless tests assert the surrounding
logic only — see `StatusBarWidgetActivationGateTest` and `SealActionGateTest`.

- [x] **The "Provenance: recording" indicator actually appears.** *(verified 2026-07-15 against
      2026.1.4.)* Open a manifest-activated project and confirm the indicator is present in the
      status bar. `refreshStatusBarWidget` delegates to `StatusBarWidgetsManager`, which installs
      widgets asynchronously through frame init; headless has no frame, so only a real IDE proves
      the student sees the disclosure. Re-check on each platform bump — this replaced a direct
      `StatusBar.addWidget` call that was removed as private API.

- [x] **The seal produces a bundle (real `extension_hash`).** *(verified 2026-07-15 against
      2026.1.4.)* In an activated project, edit and save a watched file, run
      Tools → "Provenance: Prepare Submission Bundle", and confirm a `*-bundle-*.zip` appears.
      This exercises `ownPluginDescriptor()`, which reads the plugin's own descriptor off the
      plugin class loader; under test the loader is a `PathClassLoader` and the descriptor is
      null, so CI stubs the hash via `RecorderSessionManager.extensionHashOverride`. If this
      breaks, every student's seal fails — re-check on each platform bump.

- [ ] **Seal chooser popup.** Open a project with two sibling assignment manifests, run
      Tools → Provenance: Prepare Submission Bundle, confirm a popup lists both assignment
      IDs, the one under the focused editor is pre-highlighted, and choosing one produces a
      bundle only for that assignment. This is UI (`JBPopupFactory`) that `PlatformTestUtil`
      cannot drive headlessly — same category as the external-change entries below.

## External-change detection

External-change detection (recorder PRD §4.5, and [`design.md`](design.md) §4.5) is the
port's highest-risk subsystem. Its direction, dedup (`isFromSave`), payload shape,
create/delete/reload paths, and the feeder/reload interaction are all covered by the
headless `:recorder:test` suite against a real `LocalFileSystem` temp dir.

The items below **cannot** be exercised headlessly — they need a running windowed IDE
(`./gradlew :recorder:runIde`). They are **unchecked until run manually at least once**
against each IDE version the plugin targets.

- [ ] **Frame-activation refresh fires for files changed while unfocused.** In a `runIde`
      sandbox with a manifest-activated project: alt-tab away, edit a watched file in an
      external editor (or `echo >>` from a terminal), alt-tab back, and confirm an
      `fs.external_change` appears in the session log within a few seconds — with no
      manual "Reload from disk".

- [ ] **Native watcher while the IDE stays focused.** Edit a watched file externally (a
      second-monitor terminal, or an agent running in an integrated terminal panel)
      *without* switching focus. Confirm the native OS file watcher alone delivers the
      event — students may never alt-tab away.

- [ ] **Latency.** Time the gap between an external write and the event's `wall`
      timestamp, both same-window-terminal and alt-tab. Confirm it is not multi-second,
      which would look suspicious in replay.

- [ ] **"Synchronize files on frame activation" disabled.** Confirm the native watcher
      still covers detection, and that it does not silently degrade to "only on next IDE
      restart".

- [ ] **`isFromSave()` tags every real editor save.** Confirm a normal Ctrl+S in the
      running IDE produces VFS events with `isFromSave() == true` for the saved file,
      across all target IDE versions. The `SavingRequestor` opt-in is an implementation
      detail, not a version-pinned contract; `ExternalChangeTimingTest` will catch a
      regression when next run against a newer platform.

- [ ] **Network and container filesystems** (note only). If course infrastructure ever
      runs student IDEs against a network-mounted or containerized filesystem, confirm the
      native watcher works there. This is a known IntelliJ weak spot, unrelated to this
      plugin's code.
