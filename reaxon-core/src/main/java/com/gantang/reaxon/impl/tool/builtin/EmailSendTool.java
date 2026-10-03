package com.gantang.reaxon.impl.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.gantang.reaxon.api.agent.AgentContext;
import com.gantang.reaxon.api.email.EmailProvider;
import com.gantang.reaxon.api.tool.ToolResult;
import com.gantang.reaxon.impl.tool.support.AbstractTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Send emails via a pluggable {@link EmailProvider}.
 *
 * <p>Supports plain text and HTML, multiple recipients, CC/BCC.
 *
 * <p>Design patterns:
 * <ul>
 *   <li><b>Strategy</b> — delegates sending to EmailProvider</li>
 *   <li><b>Template Method</b> — extends AbstractTool</li>
 * </ul>
 */
public class EmailSendTool extends AbstractTool {

    private static final JsonNode SCHEMA = SchemaSupport.parse("""
        {
          "type": "object",
          "properties": {
            "to": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Recipient email addresses"
            },
            "subject": {
              "type": "string",
              "description": "Email subject"
            },
            "body": {
              "type": "string",
              "description": "Email body content"
            },
            "html": {
              "type": "boolean",
              "description": "Whether body is HTML (default: false)"
            }
          },
          "required": ["to", "subject", "body"]
        }
        """);

    private final EmailProvider emailProvider;

    public EmailSendTool(EmailProvider emailProvider) {
        this.emailProvider = emailProvider;
    }

    @Override public String name()        { return "email_send"; }
    @Override public String description() { return "Send an email to one or more recipients. Requires configured email provider."; }
    @Override public JsonNode parameters(){ return SCHEMA; }
    @Override public boolean requiresApproval() { return true; }

    @Override
    protected ToolResult doExecute(String callId, Params params, AgentContext context) {
        if (emailProvider == null || !emailProvider.available()) {
            return ToolResult.failure(callId,
                "No email provider configured. Set axiflux.email.* properties to enable email sending.");
        }

        List<Object> toRaw = params.getList("to");
        if (toRaw.isEmpty()) {
            return ToolResult.failure(callId, "At least one recipient is required");
        }

        List<String> to = new ArrayList<>();
        for (Object addr : toRaw) {
            String s = String.valueOf(addr).trim();
            if (!s.isEmpty()) to.add(s);
        }
        if (to.isEmpty()) {
            return ToolResult.failure(callId, "No valid recipient addresses");
        }

        String subject = params.getString("subject");
        String body = params.getString("body");
        boolean html = params.getBool("html", false);

        String messageId = emailProvider.send(to, subject, body, html);

        String content = "Email sent.\n" +
            "From: " + emailProvider.fromAddress() + "\n" +
            "To: " + String.join(", ", to) + "\n" +
            "Subject: " + subject + "\n" +
            "Message-ID: " + messageId;

        return ToolResult.success(callId, content, Map.of(
            "messageId", messageId,
            "recipients", to,
            "subject", subject));
    }
}
