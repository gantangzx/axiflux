package com.gantang.tianshu.impl.tool.builtin;

import com.gantang.tianshu.api.agent.AgentContext;
import com.gantang.tianshu.api.skill.Skill;
import com.gantang.tianshu.api.skill.SkillMetadata;
import com.gantang.tianshu.api.skill.SkillRegistry;
import com.gantang.tianshu.api.tool.ToolResult;
import com.gantang.tianshu.impl.skill.DefaultSkillRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    static Skill fakeSkill(String name, String desc, String body) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String description() { return desc; }
            @Override public Path skillPath() { return Path.of("skills", name); }
            @Override public List<String> triggers() { return List.of("提醒", "remind"); }
            @Override public SkillMetadata metadata() { return SkillMetadata.builder(name).description(desc).build(); }
            @Override public String readContent() { return body; }
        };
    }

    private AgentContext ctx() {
        return AgentContext.builder().sessionId("t").userId("u").currentQuery("").build();
    }

    @Test
    void noRegistry_reportsHelpfully() {
        ToolResult r = new LoadSkillTool(null).execute("c", Map.of("action", "list"), ctx());
        assertTrue(r.success());
        assertTrue(r.content().contains("SKILL.md"));
    }

    @Test
    void list_showsCatalogWithTriggers() {
        SkillRegistry reg = new DefaultSkillRegistry();
        reg.register(fakeSkill("reminder", "定时提醒技能", "---\nname: reminder\n---\n# body"));
        ToolResult r = new LoadSkillTool(reg).execute("c", Map.of("action", "list"), ctx());
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("reminder"));
        assertTrue(r.content().contains("定时提醒技能"));
        assertTrue(r.content().contains("triggers"), r.content());
    }

    @Test
    void read_returnsBodyWithoutFrontMatter() {
        SkillRegistry reg = new DefaultSkillRegistry();
        reg.register(fakeSkill("reminder", "d", "---\nname: reminder\ndescription: d\n---\n# 定时提醒\n按这里的流程执行。"));
        ToolResult r = new LoadSkillTool(reg).execute("c", Map.of("action", "read", "name", "reminder"), ctx());
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.content().contains("# 定时提醒"), r.content());
        assertFalse(r.content().contains("description: d"), "front-matter should be stripped");
    }

    @Test
    void read_unknownName_listsInstalled() {
        SkillRegistry reg = new DefaultSkillRegistry();
        reg.register(fakeSkill("reminder", "d", "body"));
        ToolResult r = new LoadSkillTool(reg).execute("c", Map.of("action", "read", "name", "ghost"), ctx());
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("reminder"));
    }

    @Test
    void read_requiresName() {
        ToolResult r = new LoadSkillTool(new DefaultSkillRegistry()).execute("c", Map.of("action", "read"), ctx());
        // empty registry short-circuits before the name check; registry with a skill hits it
        SkillRegistry reg = new DefaultSkillRegistry();
        reg.register(fakeSkill("x", "d", "b"));
        ToolResult r2 = new LoadSkillTool(reg).execute("c", Map.of("action", "read"), ctx());
        assertFalse(r2.success());
        assertTrue(r2.errorMessage().contains("name"));
        assertTrue(r.success()); // empty-registry branch is fine
    }
}
