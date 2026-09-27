package com.gantang.tianshu.storage.pgvector;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Contract tests for {@link PGVectorLongTermMemory} that need no database.
 *
 * <p>Full SQL behaviour (vector insert / cosine search / compression) is
 * covered by testcontainers-based tests in environments with Docker; these
 * tests pin the two properties that must hold <em>before</em> any SQL works:
 * <ul>
 *   <li>{@link PGVectorLongTermMemory.Config} normalises missing/invalid
 *       values to safe defaults (a non-positive dimension would otherwise
 *       generate invalid {@code vector(%d)} DDL and break every statement);</li>
 *   <li>constructor DDL is fault-tolerant: a DataSource that refuses
 *       connections (or a server without pgvector) must not break application
 *       startup — the memory store logs and degrades, matching the qdrant/none
 *       deployment guarantee.</li>
 * </ul>
 */
class PGVectorLongTermMemoryTest {

    @Test
    void configDefaultsAreAppliedForMissingOrInvalidValues() {
        var c = new PGVectorLongTermMemory.Config(0, null, null, "  ");
        assertEquals(1536, c.dimension(), "non-positive dimension must fall back to 1536");
        assertEquals("https://api.openai.com/v1/embeddings", c.embedUrl());
        assertEquals("", c.embedApiKey());
        assertEquals("text-embedding-3-small", c.embedModel(), "blank model falls back to default");

        var negative = new PGVectorLongTermMemory.Config(-8, "u", "k", "m");
        assertEquals(1536, negative.dimension());

        var explicit = new PGVectorLongTermMemory.Config(768, "http://x/v1", "key", "embed-1");
        assertEquals(768, explicit.dimension());
        assertEquals("http://x/v1", explicit.embedUrl());
    }

    @Test
    void constructorSurvivesDdlFailureWhenDataSourceIsUnavailable() throws SQLException {
        // Simulates a dead database / missing pgvector extension: getConnection throws.
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));

        var memory = assertDoesNotThrow(() ->
            new PGVectorLongTermMemory(broken,
                new PGVectorLongTermMemory.Config(1536, "http://x/v1", "k", "m")));
        assertNotNull(memory);
        verify(broken, atLeastOnce()).getConnection();
    }

    @Test
    void constructorToleratesDdlStatementFailure() throws SQLException {
        // Connection succeeds but DDL execution fails (old PostgreSQL / no vector extension).
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        var stmt = mock(java.sql.Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        doThrow(new SQLException("type vector does not exist")).when(stmt).execute(anyString());

        assertDoesNotThrow(() -> new PGVectorLongTermMemory(ds,
            PGVectorLongTermMemory.Config.of(1536, "http://x/v1", "k", "m")));
    }

    // ===== P2-1: available() exposes table readiness =====

    @Test
    void availableIsFalseWhenDdlFails() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        var memory = new PGVectorLongTermMemory(broken,
            PGVectorLongTermMemory.Config.of(1536, "http://x/v1", "k", "m"));
        assertFalse(memory.available(), "available() must be false when DDL fails");
    }

    @Test
    void availableIsFalseWhenToRegclassReturnsNull() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        var stmt = mock(java.sql.Statement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        // DDL succeeds but to_regclass returns null (table not found)
        when(stmt.executeQuery("SELECT to_regclass('public.memory_items')")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(null);
        // reconcileColumnDimension queries pg_attribute via prepareStatement; stub it
        var ps = mock(java.sql.PreparedStatement.class);
        var rs2 = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs2);
        when(rs2.next()).thenReturn(false);

        var memory = new PGVectorLongTermMemory(ds,
            PGVectorLongTermMemory.Config.of(1536, "http://x/v1", "k", "m"));
        assertFalse(memory.available(), "available() must be false when to_regclass returns null");
    }

    @Test
    void availableIsTrueWhenTableVerified() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        var stmt = mock(java.sql.Statement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        // DDL succeeds and to_regclass confirms the table exists
        when(stmt.executeQuery("SELECT to_regclass('public.memory_items')")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("memory_items");
        // reconcileColumnDimension queries pg_attribute via prepareStatement; stub it
        var ps = mock(java.sql.PreparedStatement.class);
        var rs2 = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs2);
        when(rs2.next()).thenReturn(false);

        var memory = new PGVectorLongTermMemory(ds,
            PGVectorLongTermMemory.Config.of(1536, "http://x/v1", "k", "m"));
        assertTrue(memory.available(), "available() must be true when to_regclass confirms the table");
    }
}
