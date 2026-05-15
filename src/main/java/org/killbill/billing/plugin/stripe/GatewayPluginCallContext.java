/*
 * Copyright 2026 Tenchiro LLC
 *
 * Tenchiro LLC licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.killbill.billing.plugin.stripe;

import org.killbill.billing.plugin.api.PluginCallContext;
import org.joda.time.DateTime;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * GatewayPluginCallContext
 * 
 * WORKAROUND CLASS for Kill Bill call context limitations
 * 
 * Kill Bill's standard CallContext (and PluginCallContext) does NOT
 * propagate HTTP headers or query parameters from the original request down to plugins
 * when the generic notification endpoint is used.
 * 
 * This subclass extends PluginCallContext to carry both:
 * 
 *   - All HTTP headers (case-insensitive lookup)</li>
 *   - All query parameters (case-insensitive lookup)</li>
 * 
 * Use this when you need access to Stripe-Signature (or any other header/query param)
 * inside processNotification(...) without relying on MDC or duplicating code.
 */
public class GatewayPluginCallContext extends PluginCallContext {

    private final Map<String, String> headers;      // header name → value (first value if multi-valued)
    private final Map<String, String> queryParams;  // query param name → value
    /**
     * Create a CallContext that also carries all HTTP headers.
     */
    public GatewayPluginCallContext(final String createdBy,
                                        final DateTime createdDate,
                                        final UUID accountId,
                                        final UUID tenantId,
                                        final Map<String, String> headers,
                                        final Map<String, String> queryParams) {
        super(createdBy, createdDate, accountId, tenantId);
        this.headers = headers != null 
                ? Collections.unmodifiableMap(new HashMap<>(headers)) 
                : Collections.emptyMap();

        this.queryParams = queryParams != null
                ? Collections.unmodifiableMap(new HashMap<>(queryParams))
                : Collections.emptyMap();
    }

    /**
     * Get a header value (case-insensitive lookup).
     * Returns null if the header is missing.
     */
    public String getHeader(final String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return null;
        }
        final String key = headerName.toLowerCase(Locale.ROOT);
        for (final Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get a query parameter value (case-insensitive lookup).
     * Returns null if the parameter is missing.
     */
    public String getQueryParam(final String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        final String key = paramName.toLowerCase(Locale.ROOT);
        for (final Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get the raw map if you ever need all headers.
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Get the raw query parameters map (unmodifiable).
     */
    public Map<String, String> getQueryParams() {
        return queryParams;
    }
}
