package com.gantang.tianshu.spring.service;

import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.spring.config.props.SkillsProperties;
import com.gantang.tianshu.storage.entity.SkillEntity;
import com.gantang.tianshu.storage.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Skill ledger reconciliation: disk authoritative for existence, registry for enabledness. */
class SkillLedgerServiceTest {

    @TempDir
    Path tmp;

    private SkillRepository repo;
    private final List<SkillEntity> rows = new ArrayList<>();
    private final List<SkillEntity> deleted = new ArrayList<>();
    private SkillLedgerService ledger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(SkillRepository.class);
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(rows));
        when(repo.saveAll(any(Iterable.class))).thenAnswer(inv -> {
            List<SkillEntity> saved = new ArrayList<>();
            inv.getArgument(0, Iterable.class).forEach(e -> {
                SkillEntity en = (SkillEntity) e;
                rows.removeIf(r -> r.getName().equals(en.getName()));
                rows.add(en);
                saved.add(en);
            });
            return saved;
        });
        when(repo.save(any(SkillEntity.class))).thenAnswer(inv -> {
            SkillEntity en = inv.getArgument(0);
            rows.removeIf(r -> r.getName().equals(en.getName()));
            rows.add(en);
            return en;
        });
        doAnswer(inv -> {
            inv.getArgument(0, Iterable.class).forEach(e -> {
                SkillEntity en = (SkillEntity) e;
                rows.removeIf(r -> r.getName().equals(en.getName()));
                deleted.add(en);
            });
            return null;
        }).when(repo).deleteAll(any(Iterable.class));
        when(repo.count()).thenAnswer(inv -> (long) rows.size());
        when(repo.findByName(anyString())).thenAnswer(inv ->
            rows.stream().filter(r -> r.getName().equals(inv.getArgument(0))).findFirst());
        when(repo.findByEnabledFalse()).thenAnswer(inv ->
            rows.stream().filter(r -> Boolean.FALSE.equals(r.getEnabled())).toList());

        SkillsProperties sp = mock(SkillsProperties.class);
        when(sp.getRootDir()).thenReturn(tmp.toString());

        ObjectProvider<SkillRegistry> regProv = mock(ObjectProvider.class);
        when(regProv.getIfAvailable()).thenReturn(null);
        ObjectProvider<SkillExecutor> execProv = mock(ObjectProvider.class);
        when(execProv.getIfAvailable()).thenReturn(null);
        ledger = new SkillLedgerService(repo, regProv, execProv, sp);
    }

    private Skill skill(String name, String desc) {
        Skill s = mock(Skill.class);
        when(s.name()).thenReturn(name);
        when(s.description()).thenReturn(desc);
        when(s.triggers()).thenReturn(List.of());
        return s;
    }

    private void writeSkillDir(String name, String originSource) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---\n# " + name + "\n");
        if (originSource != null) {
            Files.writeString(dir.resolve(".skill-origin.json"),
                "{\"source\":\"" + originSource + "\",\"installedAt\":\"2026-09-07T00:00:00Z\"}\n");
        }
    }

    private SkillEntity row(String name, boolean enabled) {
        SkillEntity e = new SkillEntity();
        e.setName(name);
        e.setEnabled(enabled);
        return e;
    }

    @Test
    void insertsNewSkillsWithOriginAndChecksum() throws Exception {
        writeSkillDir("reminder", "git:anthropics/skills");
        SkillLedgerService.ReconcileResult r = ledger.reconcile(List.of(skill("reminder", "提醒事项")));

        assertEquals(1, r.total());
        SkillEntity entity = rows.get(0);
        assertEquals("reminder", entity.getName());
        assertEquals("git:anthropics/skills", entity.getSource());
        assertEquals("提醒事项", entity.getDescription());
        assertNotNull(entity.getChecksum());
        assertEquals(64, entity.getChecksum().length());
        assertTrue(Boolean.TRUE.equals(entity.getEnabled()));
    }

    @Test
    void skillsWithoutOriginMarkedBuiltin() throws Exception {
        writeSkillDir("local-skill", null);
        ledger.reconcile(List.of(skill("local-skill", "手工放置")));
        assertEquals("builtin", rows.get(0).getSource());
    }

    @Test
    void rowsForSkillsGoneFromDiskAreDeleted() throws Exception {
        writeSkillDir("foo", "git:o/r");
        rows.add(row("bar", true));  // bar in DB but directory absent

        SkillLedgerService.ReconcileResult r = ledger.reconcile(List.of(skill("foo", "f")));

        assertEquals(1, r.total());
        assertEquals(List.of("bar"), r.removed());
        assertEquals(1, deleted.size());
    }

    @Test
    void onDiskButNotRegisteredKeptAsDisabled() throws Exception {
        writeSkillDir("foo", "git:o/r");
        rows.add(row("foo", true));

        // Registry returns nothing for foo → disabled gate hid it.
        SkillLedgerService.ReconcileResult r = ledger.reconcile(List.of());

        assertEquals(1, r.total());
        assertTrue(r.removed().isEmpty(), "disabled skill must not be treated as orphan");
        SkillEntity foo = rows.stream().filter(x -> x.getName().equals("foo")).findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(foo.getEnabled()));
        assertEquals("git:o/r", foo.getSource());
    }

    @Test
    void disabledNamesAndSetEnabled() throws Exception {
        writeSkillDir("a", "git:o/a");
        writeSkillDir("b", "git:o/b");
        rows.add(row("a", false));
        rows.add(row("b", true));

        assertEquals(Set.of("a"), ledger.disabledNames());

        ledger.setEnabled("a", true);
        assertTrue(Boolean.TRUE.equals(
            rows.stream().filter(x -> x.getName().equals("a")).findFirst().orElseThrow().getEnabled()));
    }

    @Test
    void setEnabledUnknownSkillThrows() {
        assertThrows(IllegalArgumentException.class, () -> ledger.setEnabled("ghost", true));
    }

    @Test
    void sourceOfExcludesBuiltin() throws Exception {
        writeSkillDir("builtin-one", null);
        writeSkillDir("git-one", "git:o/repo");
        ledger.reconcile(List.of(skill("builtin-one", "x"), skill("git-one", "y")));

        assertTrue(ledger.sourceOf("builtin-one").isEmpty());
        assertEquals("git:o/repo", ledger.sourceOf("git-one").orElseThrow());
    }

    @Test
    void reconcileEmptyDiskAndEmptyRegistry() {
        SkillLedgerService.ReconcileResult r = ledger.reconcile();
        assertEquals(0, r.total());
    }
}
