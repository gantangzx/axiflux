package com.gantang.tianshu.api.codeindex;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistent semantic index over a source tree.
 *
 * <p>The store owns embedding generation and vector persistence (PGVector in
 * production); the {@code codebase_search} tool owns filesystem walking,
 * chunking and incremental-change detection. A repository root is indexed file
 * by file; each file is split into line-ranged {@link Chunk}s. Re-indexing a
 * file replaces all of its chunks atomically.
 *
 * <p>All methods are synchronous and blocking; callers run them on a worker
 * scheduler. Implementations must be safe for concurrent use.
 */
public interface CodeIndexStore {

    /** Whether the backend (extension / embedding endpoint) is usable. */
    boolean available();

    /**
     * Currently-indexed files for a root: relative path → content hash.
     * Used by the indexer to skip unchanged files and detect deletions.
     */
    Map<String, String> fileHashes(String root);

    /**
     * Replace every chunk for one file. The implementation embeds each chunk's
     * content and persists it; chunks for {@code (root, relPath)} not present in
     * the list are removed (so a re-index of a shrunk file drops stale chunks).
     */
    void upsertFile(String root, String relPath, String lang, String fileHash, List<Chunk> chunks);

    /** Remove all chunks for files that no longer exist on disk. */
    void deleteFiles(String root, Collection<String> relPaths);

    /** Semantic search. When {@code root} is null/blank, searches across all indexed roots. */
    List<CodeHit> search(String root, String query, int topK);

    /** Index statistics for a root (null/blank = all roots). */
    IndexStats stats(String root);

    /** Drop every chunk for a root (full rebuild). */
    void clearRoot(String root);

    /** A line-ranged slice of a source file. */
    record Chunk(int chunkIndex, int startLine, int endLine, String content) {}

    /** A search hit: where it is, how close (0..1 cosine similarity), and the text. */
    record CodeHit(String relPath, String lang, int startLine, int endLine,
                   double score, String snippet) {}

    /** Index size. */
    record IndexStats(String root, int files, int chunks) {}
}
