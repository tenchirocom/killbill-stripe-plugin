package org.killbill.billing.plugin.stripe;

import org.jooby.Request;
import org.jooby.Result;
import org.jooby.Results;
import org.jooby.Status;
import org.jooby.mvc.Local;
import org.jooby.mvc.POST;
import org.jooby.mvc.Path;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillClock;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.plugin.api.payment.PluginGatewayNotification;
import org.killbill.billing.plugin.stripe.dao.StripeDao;
import org.killbill.billing.plugin.stripe.dao.gen.tables.records.StripeResponsesRecord;
import org.killbill.billing.tenant.api.Tenant;
import org.killbill.billing.tenant.api.TenantApiException;
import org.killbill.billing.tenant.api.TenantUserApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collections;
import java.util.function.Supplier;
import java.util.HashMap;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Singleton;
import javax.inject.Named;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;

@Singleton
// Handle /plugins/killbill-stripe/webhook
@Path("/webhook")
public class StripeWebhookServlet {

    private static final Logger logger = LoggerFactory.getLogger(StripeWebhookServlet.class);
    private final OSGIKillbillClock clock;
    private final StripePaymentPluginApi stripePaymentPluginApi;
    private final OSGIKillbillAPI killbillAPI;
    private final StripeConfigPropertiesConfigurationHandler stripeConfigPropertiesConfigurationHandler;
    private final StripeDao stripeDao;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient notificationHttpClient = HttpClient.newHttpClient();

    @Inject
    public StripeWebhookServlet(
        final OSGIKillbillClock clock,
        final StripePaymentPluginApi stripePaymentPluginApi,
        final OSGIKillbillAPI killbillAPI,
        final StripeConfigPropertiesConfigurationHandler stripeConfigPropertiesConfigurationHandler,
        final StripeDao stripeDao
    ) {
        this.clock = clock;
        this.stripePaymentPluginApi = stripePaymentPluginApi;
        this.killbillAPI = killbillAPI;
        this.stripeConfigPropertiesConfigurationHandler = stripeConfigPropertiesConfigurationHandler;
        this.stripeDao = stripeDao;
    }

    @POST
    public Result handleWebhook(
        final Request request,
        @Local @Named("killbill_tenant") final Tenant tenant
    ) {
        // CREATE CALL CONTEXT
        // NOTE: This is the missing functionality to enable the standard processNotification
        // handler from working with Stripe. The call context has no call headers or query parameters
        // included. No way to verify the signature.
        // - build the headers map
        final Map<String, String> headersMap = new HashMap<>();
        request.headers().forEach((name, mutant) -> {
            final String value = mutant.toOptional().orElse(null);
            if (value != null) {
                headersMap.put(name, value);
            }
        });
        // - build query parameters map
        final Map<String, String> queryMap = new HashMap<>();
        request.params().toMap().forEach((name, mutant) -> {
            final String value = mutant.toOptional().orElse(null);
            if (value != null) {
                queryMap.put(name, value);
            }
        });
        final CallContext context = new GatewayPluginCallContext(
            StripeActivator.PLUGIN_NAME,
            clock.getClock().getUTCNow(),
            null,                    // accountId
            tenant.getId(),
            headersMap,
            queryMap
        );

        try {
            // GET BODY (as a string)
            String notification = request.body().value();
            //byte[] rawBody = request.body().to(byte[].class);

            // BUILD PROPERTIES (none)
            final Iterable<PluginProperty> properties = Collections.emptyList();

            // PROCESS NOTICE
            GatewayNotification result = processNotification(notification, properties, context);

            // Always return 200 OK fast so Stripe doesn't retry, unless null received
            return Results.with((result != null) ? Status.OK : Status.BAD_REQUEST);

        } catch (Exception e) {
            logger.error("[okaikei] Failed to process Stripe webhook via Jooby", e);
            return Results.with(Status.BAD_REQUEST);
        }
    }

    public GatewayNotification processNotification(final String notification,
                                                final Iterable<PluginProperty> properties,
                                                final CallContext context) throws PaymentPluginApiException {
        // Null event lambda supplier
        Supplier<String> exceptionEvent = () -> {
            return "stripe-notification-exception-event-" + System.currentTimeMillis();
        };

        logger.info("<servlet> Stripe Notification: Notification string is: {}", notification);

        // Bound check: no notification, no processing required
        if (notification == null || notification.isBlank()) {
            logger.warn("Stripe Notification: Received empty webhook payload");
            return new PluginGatewayNotification(exceptionEvent.get());
        }

        // Get tenant id from context
        UUID kbTenantId = context.getTenantId();

        // Get webhookSecret (required for authentication)
        // NOTE: Signature verification is turned off if this configuration is not set
        final StripeConfigProperties config = stripeConfigPropertiesConfigurationHandler
                .getConfigurable(kbTenantId);
        final String webhookSecret = config.getWebhookSecret();

        // Get authenticated event from notification
        Event event = getAuthenticatedEvent(notification, webhookSecret, context);
        if (event == null) {
            logger.error("Stripe Webhook: authentication failed.");
            return new PluginGatewayNotification(exceptionEvent.get());
        }

        logger.info("AUTHENTICATED!");

        try {
            //
            // PROCESS PAYMENT UPDATES
            //
            final String eventType = event.getType();
            final boolean isPartialFunding = "payment_intent.partially_funded".equals(eventType);
            logger.info("<servlet> Process status updates... type={}, id={}", eventType, event.getId());
            if ("payment_intent.succeeded".equals(eventType)
                || "payment_intent.payment_failed".equals(eventType)
                || "payment_intent.canceled".equals(eventType)
                || isPartialFunding

            ) {
                // This is currently processing only the payment_intent status changes. It will be followed
                // by a charge status change (payment_intent.succeeded then charge.succeeded). The payment_intent
                // typically reflects the payment at the convenience store, while th charge is the actual
                // charge.
                logger.info("<servlet> Payment intent processing event_type='{}'...", eventType);

                final PaymentIntent intent;
                final var deserializer = event.getDataObjectDeserializer();
                if (deserializer.getObject().isPresent()) {
                    // Attempt to get the object the normal way
                    intent = (PaymentIntent) deserializer.getObject().get();
                } else {
                    // API version mismatch — parse raw JSON directly
                    try {
                        intent = PaymentIntent.GSON.fromJson(
                            deserializer.getRawJson(), PaymentIntent.class);
                    } catch (Exception parseEx) {
                        logger.error("Stripe Webhook: failed to unpack payment intent from the notification.", parseEx);
                        //
                        // ABORT
                        //
                        return new PluginGatewayNotification(event.getId());
                    }
                }

                logger.info("... <servlet> intent={}...", intent);

                // Guard: ensure event is carrying a valid intent
                if (intent != null) {
                    final String intentId = intent.getId();
                    logger.info("<servlet> Intent not null... status={}, id={}", intent.getStatus(), intentId);

                    try {
                        // DB Lookup: get transaction by Stripe PI ID and update it
                        final StripeResponsesRecord record = stripeDao.getResponseByStripeId(intentId, kbTenantId);

                        logger.info("<servlet> CHECKPOINT-1 ... record={}", record);
                        // Guard: ensure valid record retrieved
                        if (record == null) {
                            logger.warn("Stripe Webhook: no response record found for PI {}", intentId);
                        } else {
                            //
                            // UPDATE RESPONSE
                            //
                            logger.info("<servlet> CHECKPOINT-2 ...");
                            Charge lastCharge = null;
                            if (intent.getLatestCharge() != null) {
                                try {
                                    lastCharge = Charge.retrieve(
                                        intent.getLatestCharge(),
                                        stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId()).toRequestOptions()
                                    );
                                } catch (Exception e) {
                                    logger.warn("Could not retrieve charge for PI {}", intentId, e);
                                }
                            }

                            logger.info("<servlet> CHECKPOINT-3 ... lastCharge={}", lastCharge);

                            // Update DB, Full replacement to remove any vestigual fields
                            //
                            // NOTE: the option flag REPLACE_ALL causes the updateReponse to be have as a replace response wrt the
                            // additional data. This is necessary here to prevent stale fields. Different events use different
                            // field values. When the payment intent evolves, the unused fields must be pruned.

                            final UUID kbTransactionId = UUID.fromString(record.getKbPaymentTransactionId());
                            // Updates the response with the intent data
                            final Map<String, Object> additionalDataMap = StripePluginProperties.toAdditionalDataMap(intent, lastCharge);
                            additionalDataMap.put(StripeDao.REPLACE_ALL, true);
                            StripeResponsesRecord updatedRecord = stripeDao.updateResponse(kbTransactionId, additionalDataMap, kbTenantId);

                            logger.info("<servlet> CHECKPOINT-4 ... updateRecord={}", updatedRecord);

                            // PARTIAL FUNDING
                            //
                            // Partial funding is a special case that particularly affects Japan bank transfers.
                            // Some funds have arrived, but the full amount has not been received yet. The PaymentIntent
                            // remains in requires_action state. The DB record is upated with the partial amount for
                            // the user experience, but we do NOT trigger notifyStateChange — the invoice remains unpaid
                            // and open until full funding arrives.

                            if (isPartialFunding) {
                                // PARTIAL FUNDING SCENARIO

                                logger.info("<servlet> CHECKPOINT-6 use intent directly");
                                
                                // amount_remaining is inside next_action.display_bank_transfer_instructions
                                Long amountRemaining = 0L;
                                PaymentIntent.NextAction nextAction = intent.getNextAction();
                                if (nextAction != null && nextAction.getDisplayBankTransferInstructions() != null) {
                                    amountRemaining = nextAction.getDisplayBankTransferInstructions().getAmountRemaining();
                                }

                                Long amountReceived = intent.getAmount() - amountRemaining;

                                logger.info("<servlet> CHECKPOINT-7 amount_received={}, amount_remaining={}", amountReceived, amountRemaining);

                                //
                                // Add the additional partial payment data to the response
                                //

                                final Map<String, Object> partialData = new HashMap<>();
                                partialData.put("amount_funded", amountReceived);
                                partialData.put("amount_remaining", amountRemaining);
                                partialData.put("partial_funding_event_id", event.getId());
                                stripeDao.updateResponse(kbTransactionId, partialData, kbTenantId);
                                
                                logger.info("<servlet> partial bank transfer received for PI {} — "
                                        + "received={} of {} {}. Payment remains PENDING.",
                                        intentId,
                                        amountReceived,
                                        intent.getAmount(),
                                        intent.getCurrency().toUpperCase());
                            } else {
                                // FULL FUNDING SCENARIO
                                
                                // Succeeded, failed, or canceled — trigger full state reconciliation.
                                notifyStateChange(updatedRecord, intent, properties, context);
                                logger.info("<servlet> updated response for PI {} → {}",
                                        intentId, intent.getStatus());
                            }

                            // SEND NOTIFICATION EVENT TO APPLICATION CALLBACK
                            List<String> paymentMethodTypes = intent.getPaymentMethodTypes();

                            // Check if list is not null and has at least one element
                            String stripeType = (paymentMethodTypes != null && !paymentMethodTypes.isEmpty()) 
                                                ? paymentMethodTypes.get(0) 
                                                : null;
                            if ("konbini".equals(stripeType)) {
                                notifyActionRequired(intent, "konbini", updatedRecord, context);
                            } else if ("customer_balance".equals(stripeType)) {
                                // Note: ensure 'virtualType' is defined in your current scope
                                notifyActionRequired(intent, "bank_transfer", updatedRecord, context);
                            } else {
                                logger.info(
                                    "<servlet> virtual type not recognized for user='{}', stripeType='{}', no notification message",
                                    updatedRecord.getKbAccountId(),
                                    stripeType
                                );
                            }
                        }
                    } catch (final SQLException e) {
                        logger.error("Stripe Webhook: DB error updating response, tenant_id={} intent_id={}", kbTenantId.toString(), intentId);
                    }
                    return new PluginGatewayNotification(event.getId());
                }
            }
        } catch (final Exception e) {
            logger.error("Stripe Webhook: Failed to process Stripe webhook: {}", e);
        }

        // Return a non-null notification for all cases so KillBill responds 200 to Stripe.
        // Stripe interprets any non-2xx as a failure and will retry — we never want that
        // for signature failures or unrecognised event types.
        return new PluginGatewayNotification(
            (event != null && event.getId() != null) 
                ? event.getId() 
                : exceptionEvent.get()
        );
    }

    private final Event getAuthenticatedEvent(String body, String secret, CallContext context) {
        // Bound check: secret provided
        if (secret == null || secret.isBlank()) {
            // SECURITY WARNING: if no secret is provided → signature verification is turned OFF
            logger.error("=================================================================");
            logger.error("⚠️  CRITICAL: USING DEFAULT STRIPE WEBHOOK SECRET (INSECURE!)");
            logger.error("    The property 'org.killbill.billing.plugin.stripe.webhookSecret'");
            logger.error("    is not configured. Using fallback default secret.");
            logger.error("    ADD THIS TO YOUR KILLBILL CONFIG IMMEDIATELY TO ENABLE PROPERLY:");
            logger.error("    org.killbill.billing.plugin.stripe.webhookSecret=whsec_<hexstring>");
            logger.error("=================================================================");
            // The absence of the signature is interpreted as authentication turned off. Therefore,
            // it will always return true. The above warning is provided to alert the admin
            // that the system is running in insecure developer mode.

            // extract event from the notification
            final Event event = Event.GSON.fromJson(body, Event.class);
            logger.info("Stripe Webhook, <servlet> continuing WITHOUT signature verification (dev mode)...");
            return event;
        }

        // Get signature from the call context
        String signature = null;
        if (context instanceof GatewayPluginCallContext) {
            // The natural location to access the call headers and parameters would be hte
            // call context. This is not provided in the PluginCallContext. If this is called
            // from the servlet, the servlet will add them to the specialized
            // GatewayPluginCallContext.
            GatewayPluginCallContext specialContext = (GatewayPluginCallContext) context;
            signature = specialContext.getHeader("Stripe-Signature");
            logger.info("<servlet> from context headers, signature={}", signature);
            if (signature == null) {
                signature = specialContext.getQueryParam("stripe-signature");
                logger.info("<servlet> from context query params, signature={}", signature);
            }
        } else {
            // Coming through the standard processNotification mechanism, there are not many options.
            // There is only access to the message body, and the query perameters surrepetitiously 
            // through the MDC.
            final String stripeSignatureHeader = MDC.get("req.queryString");
            signature = extractQueryParam(stripeSignatureHeader, "stripe-signature");
            logger.info("<servlet> from MDC query parameters, signature={}", signature);
        }

        // Bound check: signature found
        if (signature == null || signature.isBlank()) {
            logger.info("<servlet> NO SIGNATURE FOUND");
            return null;
        } 

        try {
            final Event event = Webhook.constructEvent(body, signature, secret);
            logger.info("<servlet> notice authenticated: event_type={}.", event.getType());
            return event;
        } catch (final SignatureVerificationException e) {
            logger.warn("Stripe Webhook: <servlet> Provided signature did not match: {}", e.getMessage());
        }

        return null;
    }

    public void notifyStateChange(StripeResponsesRecord record, PaymentIntent intent, Iterable<PluginProperty> properties, CallContext context) {
        // Immediately notify KillBill to transition the payment state.
        // This triggers KillBill to call getPaymentInfo() on this plugin right now,
        // read the PROCESSED status we just wrote, and close the invoice.
        // Without this, KillBill waits for the Janitor polling cycle (up to hours).
        try {
            final UUID kbPaymentId = UUID.fromString(record.getKbPaymentId());

            // withPluginInfo=true forces KillBill to call getPaymentInfo() on this plugin,
            // which reads the updated PROCESSED status and amount we just wrote to the DB,
            // and triggers the full payment state machine including invoice reconciliation
            // and account balance update. This is exactly what Kaui does when you click
            // the payment manually.
            killbillAPI.getPaymentApi().getPayment(
                kbPaymentId,
                true,   // withPluginInfo
                false,   // withAttempts
                Collections.emptyList(),  // pluginProperties
                context
            );

            logger.info("Stripe Webhook: notified KillBill of state change for transaction {}",
                        kbPaymentId);

        } catch (final PaymentApiException e) {
            // State transition failed — same reasoning as above.
            logger.warn("Webhook: could not notify KillBill of state change for PI {}",
                        intent.getId(), e);
        }
    }

    /**
     * Use this client for http connections to the application notification
     * callback url.
     * 
     * NOTE: Share the class static connection to conserve resources.
     */
    public void notifyActionRequired(
            final PaymentIntent intent,
            final String virtualType,
            final StripeResponsesRecord record,
            final CallContext context
    ) {
        // GET PUSH_NOTIFICATION_CB URL

        // Check plugin properties for custom push notification callback url
        final StripeConfigProperties stripeConfigProperties =
            stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId());
        String pushNotificationCb = stripeConfigProperties.getPushNotificationCb();

        // Bound check: callback url in properties
        if (pushNotificationCb == null || pushNotificationCb.isBlank()) {
            // Get notification callback from Kill Bill
            pushNotificationCb = getPushNotificationCb(context);
            // Bound check: callback url configured in killbill
            if (pushNotificationCb == null || pushNotificationCb.isBlank()) {
                // Warn: no callback notification. Kill Bill apps will not be notified of special events
                logger.warn("Stripe Plugin: No push notification configured. Unable to notify application of change.");
                return;
            }
        }

        // CONSTRUCT NOTIFICATION PAYLOAD
        final Map<String, Object> payload = buildNotificationBody(intent,virtualType,record);

        // SECURITY: Sign the payload
        final String signingSecret = stripeConfigProperties.getPushNotificationSecret();
        String jsonBody = asJson(payload);
        final String signature = signPayload(jsonBody, signingSecret.trim());

        // SEND ASYNC NOTIFICATION
        try {
            asyncSendSignedPayload(pushNotificationCb, jsonBody, signature);
        } catch (final Exception e) {
            // Non-fatal — the Janitor and Stripe webhooks are the fallback
            logger.warn("Stripe Webhook: Failed to send applicaiton notification for PI {}: {}", intent.getId(), e);
        }
    }

    private Map<String, Object> buildNotificationBody(
        final PaymentIntent intent,
        final String virtualType,
        final StripeResponsesRecord record
    ) {
        final Map<String, Object> actionDetails = 
            StripeVirtualPaymentMethods.extractNextActionDetails(intent, virtualType);

        // Build a payload that mimics the shape of a KillBill push notification
        // so your Django webhook handler needs minimal changes
        final Map<String, Object> payload = new HashMap<>();
        payload.put("eventType",   "PAYMENT_ACTION_REQUIRED");
        payload.put("accountId",   record.getKbAccountId());
        payload.put("objectType",  "PAYMENT");
        payload.put("objectId",    record.getKbPaymentId());
        payload.put("metaData",    asJson(Map.of(
            "paymentTransactionId", record.getKbPaymentTransactionId(),
            "actionType",           virtualType.toUpperCase(),
            "actionDetails",        actionDetails,
            "stripeIntentId",       intent.getId()
        )));

        return payload;
    }

    private String getPushNotificationCb(TenantContext context) {
        try {
            // Access the TenantUserApi through the global OSGIKillbillAPI
            TenantUserApi tenantApi = killbillAPI.getTenantUserApi();
            
            // Retrieve the specific system key for push notifications
            List<String> values = tenantApi.getTenantValuesForKey("PUSH_NOTIFICATION_CB", context);
            
            if (values != null && !values.isEmpty()) {
                return values.get(0); // This is your registered webhook URL
            }
        } catch (TenantApiException e) {
            logger.error("Stripe Plugin: Failed to retrieve webhook URL for tenant {}", context.getTenantId(), e);
        }
        return null;
    }

    // 2. Define the helper method
    private String asJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            // Log the error so you know if your payload is malformed
            logger.error("Stripe Plugin: Failed to serialize to JSON", e);
            return "{}"; 
        }
    }

    private String signPayload(String payload, String secret) {
        // Bound check: valid parameters
        if (payload == null || payload.isBlank() || secret == null || secret.isBlank()) {
            // If we have a key and a body, then sign the json payload.
            return "unsigned";
        }
        // Sign the payload
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            return Base64.getEncoder().encodeToString(sha256_HMAC.doFinal(payload.getBytes("UTF-8")));
        } catch (Exception e) {
            logger.error("Stripe Webhook: Notification signing failed.", e);
            return "unsigned";
        }
    }

    private void asyncSendSignedPayload(String url, String payload, String signature) {
        // Build the request
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("X-Killbill-Payload-Signature", signature)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(5))
            .build();

        final String cbUrl = url;
        notificationHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 400) {
                    logger.warn("Stripe Plugin: Notification rejected by {}, status={}", cbUrl, response.statusCode());
                } else {
                    logger.info("Stripe Plugin: Action-required notification sent, status={}", response.statusCode());
                }
            });
    }

    private String extractQueryParam(String queryString, String paramName) {
        if (queryString == null || queryString.isBlank()) return null;
        
        try {
            for (String pair : queryString.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    String key = java.net.URLDecoder.decode(kv[0].trim(), java.nio.charset.StandardCharsets.UTF_8);
                    if (paramName.equalsIgnoreCase(key)) {
                        return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("<servlet> Failed to parse query string", e);
        }
        return null;
    }

}