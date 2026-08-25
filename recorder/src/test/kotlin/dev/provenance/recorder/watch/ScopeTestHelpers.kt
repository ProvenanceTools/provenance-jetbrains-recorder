package dev.provenance.recorder.watch

import dev.provenance.core.ResolvedScope

/**
 * Test helper shared across this package's watch tests: a [ResolvedScope] whose track
 * list is exactly [paths], with no `ignore` or `attachments` rules. Most of these tests
 * predate live-membership rule evaluation and only ever cared about a flat exact-path
 * list, so this keeps them from each hand-rolling the same `ResolvedScope(...)` literal.
 * `internal` (module-wide) rather than file-private, since it is used from every file in
 * `dev.provenance.recorder.watch`'s test sources.
 */
internal fun trackOnly(vararg paths: String): ResolvedScope =
    ResolvedScope(track = paths.toList(), ignore = emptyList(), attachments = emptyList())
