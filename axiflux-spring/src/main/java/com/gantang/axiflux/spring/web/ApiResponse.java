package com.gantang.axiflux.spring.web;

/**
 * Uniform envelope for every non-streaming JSON response.
 *
 * <pre>
 *   success: { "success": true,  "data": &lt;payload&gt;, "error": null }
 *   failure: { "success": false, "data": null,      "error": "message" }
 * </pre>
 *
 * <p>Streaming endpoints (SSE chat/event streams) and protocol surfaces with
 * their own wire format (the MCP JSON-RPC endpoint) are not wrapped. Failures
 * are normally produced by {@link GlobalExceptionHandler}; controllers only
 * return success envelopes via {@link #ok}.
 */
public record ApiResponse<T>(boolean success, T data, String error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
