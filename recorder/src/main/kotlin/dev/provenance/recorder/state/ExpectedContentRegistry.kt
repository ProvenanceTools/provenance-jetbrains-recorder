package dev.provenance.recorder.state

/**
 * Maps watched relative paths to their ExpectedContent. Only files_under_review are
 * tracked (recorder PRD §4.5). Mirrors state/expected-content-registry.ts.
 *
 * Keys are workspace-relative paths using '/' separators — the same string the
 * doc.* wiring (DocWiring.relativePath) produces, so seeding from doc.open/doc.change
 * and lookups from the fs listeners agree on the key.
 */
class ExpectedContentRegistry(filesUnderReview: List<String>) {
    private val watched: Set<String> = filesUnderReview.toSet()
    private val map = HashMap<String, ExpectedContent>()

    fun isWatched(relativePath: String): Boolean = watched.contains(relativePath)

    /**
     * Get the ExpectedContent for a relative path, creating it from [initialContent]
     * on first use. Returns the existing instance if already present (initialContent
     * is then ignored).
     */
    fun getOrCreate(relativePath: String, initialContent: String): ExpectedContent =
        map.getOrPut(relativePath) { ExpectedContent(initialContent) }

    fun get(relativePath: String): ExpectedContent? = map[relativePath]

    fun delete(relativePath: String) {
        map.remove(relativePath)
    }
}
