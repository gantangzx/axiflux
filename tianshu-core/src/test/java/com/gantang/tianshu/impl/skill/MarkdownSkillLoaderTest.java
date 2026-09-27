package com.gantang.tianshu.impl.skill;

import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownSkillLoaderTest {

    private static final String SKILL_MD = """
        ---
        name: reminder
        description: Set reminders for the user
        version: 0.1.0
        triggers:
          - remind me
          - reminder
        required_tools:
          - date_time
          - http_client
        execution_mode: sequential
        steps:
          - name: parse_time
            type: llm
            target: Extract the time from the user's request
            output_var: due_time
          - name: create_task
            type: tool
            target: http_client
            params:
              method: POST
              url: http://example.com/tasks
              body: "{\\"due\\": \\"${due_time}\\"}"
        env:
          TZ: Asia/Shanghai
        ---

        # Reminder Skill

        Sets reminders for the user.
        """;

    @Test
    void parses_front_matter_and_body(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("reminder");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), SKILL_MD);

        MarkdownSkillLoader loader = new MarkdownSkillLoader();
        assertTrue(loader.supports(dir));

        Skill s = loader.load(dir);
        assertEquals("reminder", s.name());
        assertEquals("Set reminders for the user", s.description());
        assertEquals(List.of("remind me", "reminder"), s.triggers());
        SkillMetadata m = s.metadata();
        assertEquals("0.1.0", m.version());
        assertEquals(SkillMetadata.MODE_SEQUENTIAL, m.executionMode());
        assertEquals(2, m.requiredTools().size());
        assertEquals(2, m.steps().size());
        assertEquals("parse_time", m.steps().get(0).name());
        assertEquals("llm", m.steps().get(0).type());
        assertEquals("due_time", m.steps().get(0).outputVar());
        assertEquals("Asia/Shanghai", m.env().get("TZ"));

        String body = s.readContent();
        assertTrue(body.contains("Reminder Skill"));
    }

    @Test
    void loads_all_skills_recursively(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("nested/b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Files.writeString(a.resolve("SKILL.md"), "---\nname: a\ntriggers: [x]\n---\nA");
        Files.writeString(b.resolve("SKILL.md"), "---\nname: b\ntriggers: [y]\n---\nB");

        List<Skill> loaded = new MarkdownSkillLoader().loadAll(tmp);
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().anyMatch(s -> s.name().equals("a")));
        assertTrue(loaded.stream().anyMatch(s -> s.name().equals("b")));
    }

    @Test
    void handles_missing_front_matter(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("noyaml");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# Hello\n\nA simple skill.\n");

        Skill s = new MarkdownSkillLoader().load(dir);
        assertEquals("noyaml", s.name());
        assertEquals("A simple skill.", s.description());
        assertTrue(s.metadata().steps().isEmpty());
    }

    @Test
    void accepts_camelCase_front_matter_keys(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("camel");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
            ---
            name: reminder
            triggers: [remind]
            requiredTools:
              - scheduler
              - datetime
            executionMode: llm_guided
            ---
            body
            """);

        Skill s = new MarkdownSkillLoader().load(dir);
        assertEquals(SkillMetadata.MODE_LLM_GUIDED, s.metadata().executionMode());
        assertEquals(List.of("scheduler", "datetime"), s.metadata().requiredTools());
    }

    @Test
    void tolerates_utf8_bom_in_front_matter(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Windows editors / PowerShell-created zips prepend a UTF-8 BOM (EF BB BF);
        // the front-matter delimiter must still be recognised.
        Path dir = tmp.resolve("bomskill");
        Files.createDirectories(dir);
        byte[] bom = "\uFEFF".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] rest = "---\nname: bomskill\nversion: 1.0.1\ndescription: BOM test\n---\nbody\n"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + rest.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(rest, 0, all, bom.length, rest.length);
        Files.write(dir.resolve("SKILL.md"), all);

        Skill s = new MarkdownSkillLoader().load(dir);
        assertEquals("bomskill", s.name());
        assertEquals("1.0.1", s.metadata().version());
        assertEquals("BOM test", s.description());
    }
}
