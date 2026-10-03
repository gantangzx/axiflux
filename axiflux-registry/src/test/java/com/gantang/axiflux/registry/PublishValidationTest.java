package com.gantang.axiflux.registry;

import com.gantang.axiflux.registry.service.PublishService;
import com.gantang.axiflux.registry.skill.SkillFrontmatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublishValidationTest {

    @Test
    void slugifyNormalizesNames() {
        assertEquals("pdf-tools", PublishService.slugify("PDF_Tools"));
        assertEquals("my-cool-skill", PublishService.slugify("My Cool Skill!!"));
        assertEquals("a-b", PublishService.slugify("a.b"));
    }

    @Test
    void slugifyRejectsTooShort() {
        assertThrows(Exception.class, () -> PublishService.slugify("!!"));
    }

    @Test
    void versionCompare() {
        assertTrue(PublishService.compareVersions("1.2.0", "1.1.9") > 0);
        assertTrue(PublishService.compareVersions("1.0.1", "1.0.2") < 0);
        assertEquals(0, PublishService.compareVersions("2.0.0", "2.0.0"));
    }

    @Test
    void frontmatterScalarsAndBlockLists() {
        String md = """
            ---
            name: demo-skill
            version: 1.3.0
            description: A demo
            tags:
              - pdf
              - docs
            triggers:
              - "pdf 转换"
            required_tools: [file_read, code_executor]
            ---
            # body
            """;
        Map<String, Object> fm = SkillFrontmatter.parse(md);
        assertEquals("demo-skill", SkillFrontmatter.str(fm, "name"));
        assertEquals("1.3.0", SkillFrontmatter.str(fm, "version"));
        assertEquals(List.of("pdf", "docs"), SkillFrontmatter.list(fm, "tags"));
        assertEquals(List.of("pdf 转换"), SkillFrontmatter.list(fm, "triggers"));
        assertEquals(List.of("file_read", "code_executor"), SkillFrontmatter.list(fm, "required_tools"));
    }

    @Test
    void frontmatterMissing() {
        assertTrue(SkillFrontmatter.parse("# just markdown").isEmpty());
        assertTrue(SkillFrontmatter.parse(null).isEmpty());
    }

    @Test
    void frontmatterCamelCaseAlias() {
        String md = "---\nname: x\nrequiredTools:\n  - bash\n---\n";
        Map<String, Object> fm = SkillFrontmatter.parse(md);
        assertEquals(List.of("bash"), SkillFrontmatter.list(fm, "required_tools", "requiredTools"));
    }
}
