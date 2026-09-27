package com.gantang.tianshu.spring.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillExecutor;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.spring.config.props.SkillsProperties;
import com.gantang.tianshu.storage.entity.SkillEntity;
import com.gantang.tianshu.storage.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Skill install ledger (本地台账, roadmap Sprint A).
 *
 * <p>The filesystem ({@code skills/<name>/}) is the runtime source of truth for
 * <i>content</i>; the {@code skills} table mirrors it and additionally owns the
 * <b>enabled/disabled</b> state (filesystem has no such concept). Reconciliation
 * therefore keys on disk directories rather than on the live registry:
 *
 * <ul>
 *   <li>on disk + registered (enabled) → upsert, {@code enabled=true}</li>
 *   <li>on disk + not registered (disabled via ledger) → keep row, {@code enabled=false}</li>
 *   <li>gone from disk → delete row (real uninstall)</li>
 * </ul>
 *
 * <p>The disabled set is fed to {@link com.gantang.tianshu.api.skill.SkillExecutor}
 * as a snapshot
 * supplier: on every reload, disabled skills are content-tracked but never
 * registered, so {@code load_skill list} and the agent catalog both hide them.
 */
public class SkillLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SkillLedgerService.class);
    private static final String ORIGIN_FILE = ".skill-origin.json";

    /** Parsed .skill-origin.json contents. */
    private record Origin(String source, String version) {}

    private final SkillRepository repo;
    private final ObjectProvider<SkillRegistry> registryProvider;
    private final ObjectProvider<SkillExecutor> executorProvider;
    private final SkillsProperties skillsProps;
    private final ObjectMapper om = new ObjectMapper();

    public SkillLedgerService(SkillRepository repo,
                              ObjectProvider<SkillRegistry> registryProvider,
                              ObjectProvider<SkillExecutor> executorProvider,
                              SkillsProperties skillsProps) {
        this.repo = repo;
        this.registryProvider = registryProvider;
        this.executorProvider = executorProvider;
        this.skillsProps = skillsProps;
    }

    /** Reconciliation outcome: rows upserted (names), orphan rows removed, total rows now. */
    public record ReconcileResult(List<String> upserted, List<String> removed, int total) {}

    /** Reconcile against the currently loaded registry + filesystem. */
    public ReconcileResult reconcile() {
        SkillRegistry reg = registryProvider.getIfAvailable();
        List<Skill> skills = reg != null ? List.copyOf(reg.all()) : List.of();
        return reconcile(skills);
    }

    /** Full reconciliation: disk directories are authoritative for existence; registry for enabledness. */
    public ReconcileResult reconcile(List<Skill> loaded) {
        Path root = skillsRoot();
        Set<String> onDisk = diskSkillNames(root);
        Map<String, Skill> loadedByName = new LinkedHashMap<>();
        for (Skill s : loaded) loadedByName.put(s.name(), s);

        Map<String, SkillEntity> existing = new LinkedHashMap<>();
        repo.findAll().forEach(e -> existing.put(e.getName(), e));

        Set<String> seen = new HashSet<>();
        List<SkillEntity> toSave = new ArrayList<>();
        List<String> upserted = new ArrayList<>();

        for (String name : onDisk) {
            seen.add(name);
            Path dir = root.resolve(name).normalize();
            Skill s = loadedByName.get(name);
            boolean enabled = s != null;
            Origin origin = readOrigin(dir);
            String source = origin != null ? origin.source() : null;
            String checksum = sha256(dir.resolve("SKILL.md"));

            SkillEntity e = existing.get(name);
            boolean isNew = e == null;
            if (isNew) {
                e = new SkillEntity();
                e.setName(name);
            }
            if (s != null) {
                e.setDescription(s.description());
                e.setTriggers(s.triggers() != null ? List.copyOf(s.triggers()) : List.of());
            }
            e.setPath(dir.toString());
            if (source != null) {
                e.setSource(source);
            } else if (isNew && e.getSource() == null) {
                e.setSource("builtin");
            }
            e.setChecksum(checksum);
            // Installed version: for federated packages (registry:/clawhub:) the origin record
            // is authoritative — it is written from the resolved package version at install/update,
            // while SKILL.md frontmatter often omits version and the loader defaults to 0.0.1 (which
            // would make every catalog release look like an update). Other skills prefer frontmatter.
            String metaVersion = (s != null && s.metadata() != null) ? s.metadata().version() : null;
            boolean federated = source != null
                && (source.startsWith("registry:") || source.startsWith("clawhub:"));
            String version = federated
                ? (origin != null && origin.version() != null && !origin.version().isBlank()
                    ? origin.version() : metaVersion)
                : ((metaVersion != null && !metaVersion.isBlank())
                    ? metaVersion : (origin != null ? origin.version() : null));
            if (version != null && !version.isBlank()) e.setVersion(version);
            // Registry membership reflects the disabled gate; trust it for enabled state.
            e.setEnabled(enabled);
            toSave.add(e);
            upserted.add(name);
        }

        List<String> removed = new ArrayList<>();
        List<SkillEntity> toDelete = new ArrayList<>();
        for (SkillEntity e : new ArrayList<>(existing.values())) {
            if (!seen.contains(e.getName())) {
                removed.add(e.getName());
                toDelete.add(e);
            }
        }

        if (!toSave.isEmpty()) repo.saveAll(toSave);
        if (!toDelete.isEmpty()) repo.deleteAll(toDelete);

        int total = (int) repo.count();
        if (!upserted.isEmpty() || !removed.isEmpty()) {
            log.info("Skill ledger reconciled: upserted={} removed={} total={}",
                upserted.size(), removed, total);
        }
        return new ReconcileResult(List.copyOf(upserted), List.copyOf(removed), total);
    }

    /** Names of skills currently disabled in the ledger. */
    public Set<String> disabledNames() {
        Set<String> out = new LinkedHashSet<>();
        repo.findByEnabledFalse().forEach(e -> out.add(e.getName()));
        return out;
    }

    /**
     * Wire the disabled-names gate into the workflow executor (idempotent).
     * Call once at startup before the first reconcile; the supplier is re-evaluated
     * on every reload, so later enable/disable changes take effect on next reload.
     */
    public void applyDisabledFilter() {
        SkillExecutor ex = executorProvider.getIfAvailable();
        if (ex != null) {
            ex.setDisabledNamesSupplier(this::disabledNames);
        }
    }

    /** Flip enabled state. Caller is responsible for triggering a reload + reconcile afterwards. */
    public SkillEntity setEnabled(String name, boolean enabled) {
        SkillEntity e = repo.findByName(name)
            .orElseThrow(() -> new IllegalArgumentException("技能不在台账中（未安装）: " + name));
        e.setEnabled(enabled);
        return repo.save(e);
    }

    /** Reload skills from disk (disabled gate re-evaluated) then reconcile the ledger. */
    public ReconcileResult reloadAndReconcile() {
        SkillExecutor ex = executorProvider.getIfAvailable();
        if (ex != null) ex.reloadSkills();
        return reconcile();
    }

    /** Install origin recorded for a skill, if any. */
    public Optional<String> sourceOf(String name) {
        return repo.findByName(name)
            .map(SkillEntity::getSource)
            .filter(s -> s != null && !s.isBlank() && !"builtin".equals(s));
    }

    /** Ledger rows keyed by skill name (for REST/UI enrichment). */
    public Map<String, SkillEntity> ledgerByName() {
        Map<String, SkillEntity> out = new LinkedHashMap<>();
        repo.findAll().forEach(e -> out.put(e.getName(), e));
        return out;
    }

    /** skill name → install source (builtin/empty for non-tracked); used by update-all. */
    public Map<String, String> sourceBySkill() {
        Map<String, String> out = new LinkedHashMap<>();
        repo.findAll().forEach(e -> out.put(e.getName(), e.getSource()));
        return out;
    }

    /** First-level subdirectories of the skills root that contain a SKILL.md. */
    private Set<String> diskSkillNames(Path root) {
        Set<String> names = new LinkedHashSet<>();
        if (!Files.isDirectory(root)) return names;
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(dir -> {
                if (Files.isRegularFile(dir.resolve("SKILL.md"))) {
                    names.add(dir.getFileName().toString());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list skills root {}: {}", root, e.toString());
        }
        return names;
    }

    private Path skillsRoot() {
        String dir = skillsProps != null ? skillsProps.getRootDir() : null;
        Path root = Paths.get(dir != null && !dir.isBlank() ? dir : "./skills")
            .toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            log.warn("Cannot create skills root {}: {}", root, e.toString());
        }
        return root;
    }

    /** Read {@code source}/{@code version} from a skill's .skill-origin.json; null when absent/invalid. */
    private Origin readOrigin(Path skillDir) {
        Path f = skillDir.resolve(ORIGIN_FILE);
        if (!Files.isRegularFile(f)) return null;
        try {
            JsonNode n = om.readTree(Files.readString(f, StandardCharsets.UTF_8));
            String src = n.path("source").asText(null);
            String ver = n.path("version").asText(null);
            if ((src == null || src.isBlank()) && (ver == null || ver.isBlank())) return null;
            return new Origin(
                (src != null && !src.isBlank()) ? src : null,
                (ver != null && !ver.isBlank()) ? ver : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 hex of SKILL.md bytes, or null when unreadable. */
    private String sha256(Path skillMd) {
        if (!Files.isRegularFile(skillMd)) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(skillMd));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
