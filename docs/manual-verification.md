# Manual verification — external-change detection

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
