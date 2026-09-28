package com.gantang.tianshu.api.workflow;

import java.time.Duration;

/**
 * Configuration for a {@link NodeKind#APPROVAL} node.
 *
 * @param subject      what is being approved (used as the tool name / label)
 * @param description  human-readable description shown to the approver (template)
 * @param timeout      max wait before the node follows the {@link #onReject()} route
 * @param onApprove    node id to jump to when approved
 * @param onReject     node id to jump to when rejected or timed out
 */
public record ApprovalSpec(
    String subject,
    String description,
    Duration timeout,
    String onApprove,
    String onReject
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String subject = "approval";
        private String description = "";
        private Duration timeout;
        private String onApprove;
        private String onReject;

        public Builder subject(String v) { this.subject = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }
        public Builder onApprove(String v) { this.onApprove = v; return this; }
        public Builder onReject(String v) { this.onReject = v; return this; }

        public ApprovalSpec build() {
            return new ApprovalSpec(subject, description, timeout, onApprove, onReject);
        }
    }
}
