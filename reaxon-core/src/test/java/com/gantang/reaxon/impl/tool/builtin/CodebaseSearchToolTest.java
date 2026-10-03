package com.gantang.reaxon.impl.tool.builtin;

import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.codeindex.CodeIndexStore;
import com.gantang.reaxon.api.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CodebaseSearchToolTest {

    /** In-memory CodeIndexStore capturing upserts for assertions. */
    static final class FakeStore implements CodeIndexStore {
        boolean available = true;
        final Map<String, Map<String, String>> hashes = new HashMap<>();   // root -> rel -> hash
        final Map<String, List<Chunk>> chunksByFile = new HashMap<>();      // root|rel -> chunks

        @Override public boolean available() { return available; }
        @Override public Map<String, String> fileHashes(String root) {
            return hashes.getOrDefault(root, new HashMap<>());
        }
        @Override public void upsertFile(String root, String rel, String lang, String fileHash, List<Chunk> chunks) {
            hashes.computeIfAbsent(root, k -> new HashMap<>()).put(rel, fileHash);
            chunksByFile.put(root + "|" + rel, new ArrayList<>(chunks));
        }
        @Override public void deleteFiles(String root, Collection<String> relPaths) {
            Map<String, String> m = hashes.get(root);
            if (m != null) relPaths.forEach(m::remove);
        }
        @Override public List<CodeHit> search(String root, String query, int topK) {
            return List.of(new CodeHit("src/Foo.java", "java", 12, 20, 0.87, "void authenticate() {\n  checkToken();\n}"));
        }
        @Override public IndexStats stats(String root) {
            int files = hashes.values().stream().mapToInt(Map::size).sum();
            int chunks = chunksByFile.values().stream().mapToInt(List::size).sum();
            return new IndexStats(root == null ? "(all)" : root, files, chunks);
        }
        @Override public void clearRoot(String root) { hashes.remove(root); }
    }

    private AgentContext ctx() {
        return AgentContext.builder().sessionId("t").userId("u").currentQuery("").build();
    }

    @Test
    void chunk_splitsLongFileWithLineRanges() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) sb.append("line ").append(i).append("\n");
        List<CodeIndexStore.Chunk> chunks = CodebaseSearchTool.chunk(sb.toString());
        assertTrue(chunks.size() > 1, "200 lines should produce multiple chunks");
        assertEquals(1, chunks.get(0).startLine());
        for (CodeIndexStore.Chunk c : chunks) {
            assertTrue(c.startLine() >= 1 && c.endLine() >= c.startLine());
            assertFalse(c.content().isBlank());
        }
        assertEquals(200, chunks.get(chunks.size() - 1).endLine());
    }

    @Test
    void index_embedsSourceAndSkipsSecretsAndBinaries(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("App.java"), "class App { int add(int a,int b){return a+b;} }\n");
        Files.writeString(dir.resolve(".env"), "API_KEY=should-not-be-indexed\n");
        Files.writeString(dir.resolve("logo.png"), "not-a-real-png");
        Path sub = Files.createDirectory(dir.resolve("target"));
        Files.writeString(sub.resolve("Gen.java"), "generated\n"); // build dir skipped

        FakeStore store = new FakeStore();
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(dir), store);
        ToolResult r = tool.execute("c1", Map.of("action", "index", "path", dir.toString()), ctx());

        assertTrue(r.success(), r.content());
        Map<String, String> indexed = store.hashes.get(dir.toString());
        assertNotNull(indexed);
        assertTrue(indexed.containsKey("App.java"), "source file indexed");
        assertFalse(indexed.containsKey(".env"), "secret file must not be indexed");
        assertFalse(indexed.containsKey("logo.png"), "binary must not be indexed");
        assertFalse(indexed.containsKey("target/Gen.java"), "build dir must be skipped");
        List<CodeIndexStore.Chunk> chunks = store.chunksByFile.get(dir + "|App.java");
        assertFalse(chunks.isEmpty());
    }

    @Test
    void index_incrementalSkipsUnchangedFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.java"), "class A {}\n");
        FakeStore store = new FakeStore();
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(dir), store);
        ToolResult first = tool.execute("c1", Map.of("action", "index", "path", dir.toString()), ctx());
        assertTrue(first.success());
        int chunksAfterFirst = store.chunksByFile.size();

        ToolResult second = tool.execute("c2", Map.of("action", "index", "path", dir.toString()), ctx());
        assertTrue(second.success());
        assertTrue(second.content().contains("files unchanged: 1"), second.content());
        assertEquals(chunksAfterFirst, store.chunksByFile.size());
    }

    @Test
    void search_reportsHitsWithLocations(@TempDir Path dir) {
        FakeStore store = new FakeStore();
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(dir), store);
        ToolResult r = tool.execute("c1", Map.of("action", "search", "query", "where is auth", "path", dir.toString()), ctx());
        assertTrue(r.success(), r.content());
        assertTrue(r.content().contains("src/Foo.java"), r.content());
        assertTrue(r.content().contains("L12-20"), r.content());
    }

    @Test
    void search_requiresQuery(@TempDir Path dir) {
        FakeStore store = new FakeStore();
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(dir), store);
        ToolResult r = tool.execute("c1", Map.of("action", "search", "path", dir.toString()), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("query"));
    }

    @Test
    void unavailableStore_failsClearly(@TempDir Path dir) {
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(dir), null);
        ToolResult r = tool.execute("c1", Map.of("action", "search", "query", "x"), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("pgvector"));
    }

    /** toolsec P2-7: tenant workspaces scope indexing to the caller's root, never the global roots. */
    @Test
    void index_withWorkspaces_scopesToTenantRoot(@TempDir Path base) throws Exception {
        var ws = new com.gantang.reaxon.impl.tool.support.TenantWorkspaces(base, false);
        Path tenantRoot = ws.rootFor("alice");
        Path global = Files.createDirectory(base.resolve("shared-global"));
        Files.writeString(tenantRoot.resolve("Mine.java"), "class Mine {}\n");
        Files.writeString(global.resolve("Theirs.java"), "class Theirs {}\n");

        FakeStore store = new FakeStore();
        // The tool's configured (global) root spans BOTH trees — without workspace
        // scoping, a tenant caller could index the whole base.
        CodebaseSearchTool tool = new CodebaseSearchTool(List.of(base), store).withWorkspaces(ws);
        AgentContext alice = AgentContext.builder()
            .sessionId("t").userId("alice").currentQuery("").build();

        // Even though 'global' sits inside the configured root, the tenant scope
        // (combineGlobalRoots=false) restricts the caller to their own tree.
        ToolResult refused = tool.execute("w1", Map.of(
            "action", "index", "path", global.toString()), alice);
        assertFalse(refused.success());
        assertTrue(refused.errorMessage().contains("outside allowed roots"), refused.errorMessage());

        // Indexing the tenant's own tree works and never picks up the global files.
        ToolResult ok = tool.execute("w2", Map.of(
            "action", "index", "path", tenantRoot.toString()), alice);
        assertTrue(ok.success(), ok.errorMessage());
        Map<String, String> indexed = store.hashes.get(tenantRoot.toString());
        assertNotNull(indexed);
        assertTrue(indexed.containsKey("Mine.java"));
        assertNull(store.hashes.get(global.toString()), "global root must not be indexed by a tenant caller");
    }
}
