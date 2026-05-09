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

/*
 * StripeVirtualPaymentMethods.java
 * ===========================
 * Package: org.killbill.billing.plugin.stripe
 *
 * PURPOSE
 * -------
 * Static utility class that extends the Stripe plugin to support virtual
 * payment methods — specifically Konbini and Bank Transfer — which
 * are fundamentally incompatible with the plugin's standard card/SEPA flow.
 *
 * WHY THESE METHODS NEED SPECIAL HANDLING
 * ----------------------------------------
 * Standard card payments in this plugin work as follows:
 *   1. addPaymentMethod() → attaches a reusable Stripe PaymentMethod to the customer
 *   2. purchasePayment()  → creates a PaymentIntent referencing that stored PaymentMethod ID
 *   3. Result is immediate: SUCCESS or FAILED
 * 
 * Kill Bill expects payment methods to be reusable. They are defined once, and then used
 * repeatedly for payments. They are mostly immutable, with the exception of setting the
 * default payment method, the method used automatically.
 * 
 * In Stripe, payment methods like Konbini and Bank Transfers are different. They break this
 * model in some fundamental ways:
 *
 *   a) SINGLE-USE: No reusable PaymentMethod ID exists. The payment details
 *      (konbini voucher, virtual bank account) are generated fresh per transaction
 *      inside the PaymentIntent itself, not attached to the Stripe Customer.
 *
 *   b) DEFERRED: Funds arrive hours or days later. The PaymentIntent enters
 *      `requires_action` status after confirmation, meaning KillBill must treat
 *      the transaction as PENDING and poll for the final outcome.
 *
 *   c) CUSTOMER-DETAIL DEPENDENT: Konbini requires the customer's full name and
 *      email at PaymentIntent creation time. Bank transfer requires the customer's
 *      email. These details must be stored at addPaymentMethod() time (in the
 *      plugin's additional_data column) and retrieved at purchasePayment() time.
 *
 * DESIGN APPROACH
 * ---------------
 * Rather than attempty to adapt the Kill Bill payment method model to include
 * virtual, transient payment types, a 'virtual' payment type provides a
 * persistent payment method object from which transient types can be created as
 * necessary. Customer information necessary for the creation of these transient
 * methods are stored as the payment method properties. When a concrete Stripe
 * payment method is needed to complete a payment request, they are created from
 * this information.
 * 
 * The StripePaymentPluginApi has been modified to intercept key decision points,
 * determine if the active payment method is a a virtual or natural type and
 * delegates the type-specific logic for virtual methods to this class.
 * 
 * The virtual method, once created, can be used the same way as a natural Stripe
 * payment method, and all other payment paths remain unchanged.
 *
 * STORAGE SENTINEL
 * ----------------
 * Virtual methods have no real Stripe PaymentMethod ID to store in the
 * stripe_payment_methods.stripe_id column. Therefore a synthetic sentinel
 * is used:
 *
 *   "virtualpm_konbini"       for Konbini
 *   "virtualpm_bank_transfer" for Bank Transfer
 *
 * This sentinel is detectable via isVirtualStripeId(), which is used by
 * deletePaymentMethod() and getPaymentMethods() to avoid making Stripe API
 * calls with an invalid ID.
 *
 * The actual payment credentials (name, email, phone) are stored in the
 * additional_data JSON column alongside a "virtualpm_type" key so they
 * can be retrieved at purchasePayment() time.
 *
 * STRIPE PAYMENT FLOW FOR EACH TYPE
 * -----------------------------------
 *
 * Konbini:
 *   1. Create PaymentIntent with:
 *        payment_method_types: ["konbini"]
 *        payment_method_data:  {type: "konbini"}
 *        payment_method_options.konbini: {product_description, expires_after_days}
 *        confirm: false
 *        confirmation_method: "automatic"
 *   2. Confirm the intent with:
 *        payment_method_data.konbini.customer_name
 *        payment_method_data.konbini.confirmation_number  (optional)
 *   3. Response: status="requires_action",
 *        next_action.type="konbini_display_details"
 *        next_action.konbini_display_details.confirmation_number — give to customer
 *   4. Customer pays at store → Stripe fires payment_intent.succeeded webhook
 *
 * Bank Transfer (customer_balance):
 *   1. Create PaymentIntent with:
 *        payment_method_types: ["customer_balance"]
 *        payment_method_data:  {type: "customer_balance"}
 *        payment_method_options.customer_balance:
 *          {funding_type: "bank_transfer", bank_transfer: {type: "jp_bank_transfer"}}
 *        confirm: false
 *        confirmation_method: "automatic"
 *   2. Confirm the intent (no extra data required)
 *   3. Response: status="requires_action",
 *        next_action.type="display_bank_transfer_instructions"
 *        next_action.display_bank_transfer_instructions — give bank details to customer
 *   4. Customer transfers → Stripe reconciles → payment_intent.succeeded webhook
 */
package org.killbill.billing.plugin.stripe;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.catalog.api.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.security.SecureRandom;


public final class StripeVirtualPaymentMethods {

    private static final Logger logger = LoggerFactory.getLogger(StripeVirtualPaymentMethods.class);

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /**
     * Key used in the stripe_payment_methods.additional_data JSON column to
     * identify a virtual payment method type. This is the key that
     * StripePaymentPluginApi reads in both addPaymentMethod() and purchasePayment()
     * to determine whether special handling is needed.
     *
     * Value example: "konbini" or "bank_transfer"
     */
    public static final String METHOD_TYPE = "type";

    /**
     * Prefix prepended to the type key to form the sentinel stripeId stored in
     * stripe_payment_methods.stripe_id for virtual methods.
     *
     * We cannot store null or empty here because the column has a NOT NULL
     * constraint and is used as an identifier in some queries. A prefixed
     * sentinel is detectable by isSingleUseStripeId() and is safe to store.
     */
    public static final String SINGLE_USE_STRIPE_ID_PREFIX = "singleuse_";

    /**
     * The allowable characters used in the random string postfixed to the single use
     * method id. Mimicks the Stripe id pattern.
     */
    private static final String ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /**
     * Random seed for the stripe id generator.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The complete set of virtual payment method type keys this class handles.
     * Any value in this set returned by getSingleUseType() triggers special handling
     * in addPaymentMethod(), purchasePayment(), deletePaymentMethod(), and
     * getPaymentMethods().
     */
    public static final Set<String> SINGLE_USE_TYPES = ImmutableSet.of("konbini", "bank_transfer");

    /**
     * Stripe's internal type identifier for bank transfers via customer balance.
     * Stripe calls this "customer_balance", not "bank_transfer". When building
     * the PaymentIntent's payment_method_types array, we must use Stripe's name.
     */
    private static final String STRIPE_BANK_TRANSFER_TYPE = "customer_balance";

    /**
     * Number of days after which a Konbini voucher expires. Stripe requires this
     * at PaymentIntent creation time. Aligns with OfflinePaymentType.KONBINI.expiryDuration.
     */
    private static final long KONBINI_EXPIRES_AFTER_DAYS = 3L;

    /**
     * Bank transfer sub-type for Japan. Passed in payment_method_options.customer_balance
     * to tell Stripe which bank transfer rails to use.
     */
    private static final String JP_BANK_TRANSFER_TYPE = "jp_bank_transfer";

    // Utility class — no instances.
    private StripeVirtualPaymentMethods() {}

    // -----------------------------------------------------------------------
    // Type detection
    // -----------------------------------------------------------------------

    /**
     * Extract the virtual type from plugin properties passed to addPaymentMethod()
     * or any other call site where the type comes in as a PluginProperty list.
     *
     * Returns null if no virtual type property is present, allowing the caller
     * to fall through to standard card/SEPA handling.
     *
     * @param properties  Plugin properties from the KillBill API call.
     * @return            The type key ("konbini", "bank_transfer"), or null.
     */
    public static String getSingleUseType(final Iterable<PluginProperty> properties) {
        final String value = PluginProperties.findPluginPropertyValue(METHOD_TYPE, properties);
        return SINGLE_USE_TYPES.contains(value) ? value : null;
    }

    /**
     * Extract the virtual type from an additionalData map previously stored in
     * the stripe_payment_methods table and decoded from JSON.
     *
     * Called at purchasePayment() time, when the payment method record is retrieved
     * from the DB and its additionalData is deserialized.
     *
     * @param additionalData Deserialized additional_data map from the DB record.
     * @return The type key ("konbini", "bank_transfer"), or null.
     */
    public static String getSingleUseType(final Map<String, Object> additionalData) {
        if (additionalData == null) {
            return null;
        }

        // Primary method: look for the explicit single_use_type key
        final Object value = additionalData.get(METHOD_TYPE);
        if (value instanceof String && SINGLE_USE_TYPES.contains(value)) {
            return (String) value;
        }

        // Fallback: try to extract from sentinel stripe_id (in case additional_data is incomplete)
        final String stripeId = (String) additionalData.get("id");
        if (stripeId != null && stripeId.startsWith(SINGLE_USE_STRIPE_ID_PREFIX)) {
            // e.g. singleuse_konbini_1TKsXURWcYTdjAvFKxEWr585
            for (String type : SINGLE_USE_TYPES) {
                if (stripeId.contains("_" + type + "_")) {
                    return type;
                }
            }
        }

        return null;
    }

    /**
     * Return true if the given type key requires special PaymentIntent handling.
     *
     * This is a guard used in StripePaymentPluginApi.executeInitialTransaction()
     * to branch from the standard card flow into the virtual flow. A null
     * argument (no virtual type detected) returns false, leaving the standard
     * path unchanged.
     *
     * @param singleUseType  Value from getSingleUseType(), may be null.
     * @return               true if special handling is needed.
     */
    public static boolean requiresSpecialHandling(final String singleUseType) {
        return singleUseType != null && SINGLE_USE_TYPES.contains(singleUseType);
    }

    public static boolean validateSpecialHandlingCurrency(final String singleUseType, final Currency currency) {
        // **FIX** Improve this by refining the test for each singleUseType in the event that special methods
        // that apply to other currencies are automatically supported.
        return currency != Currency.JPY;
    }

    /**
     * Return true if the given stripeId is a synthetic sentinel value for a
     * virtual method, rather than a real Stripe PaymentMethod ID.
     *
     * Used by:
     *   deletePaymentMethod() — to skip the Stripe detach() API call.
     *   getPaymentMethods()   — to exclude virtual records from the Stripe
     *                           refresh sync loop (which would otherwise delete them).
     *
     * @param stripeId  The value from stripe_payment_methods.stripe_id.
     * @return          true if this is a sentinel ID, not a real Stripe ID.
     */
    public static boolean isSingleUseStripeId(final String stripeId) {
        if (stripeId == null) {
            return false;
        }
        return stripeId.startsWith(SINGLE_USE_STRIPE_ID_PREFIX);
    }

    /**
     * Build the sentinel stripeId for storage in stripe_payment_methods.stripe_id.
     *
     * @param singleUseType  The type key ("konbini", "bank_transfer").
     * @return               The sentinel string, e.g. "singleuse_konbini".
     */
    public static String buildSentinelStripeId(final String singleUseType) {
        StringBuilder sb = new StringBuilder(24);

        for (int i = 0; i < 24; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return SINGLE_USE_STRIPE_ID_PREFIX + singleUseType + "_" + sb.toString();
    }

    // -----------------------------------------------------------------------
    // addPaymentMethod() support
    // -----------------------------------------------------------------------

    /**
     * Validate and extract the additional data to store for a virtual payment
     * method being registered via addPaymentMethod().
     *
     * For Konbini: requires "fullname" and "email" in properties. "phone" is optional.
     * For Bank Transfer: requires "email" in properties.
     *
     * The returned map is stored as JSON in stripe_payment_methods.additional_data.
     * It will be retrieved by purchasePayment() to populate the PaymentIntent.
     *
     * @param singleUseType  The type key, already validated by getSingleUseType().
     * @param properties     Plugin properties from addPaymentMethod() call.
     * @return               A map ready for storage in additional_data.
     * @throws IllegalArgumentException if required fields are missing.
     */
    public static Map<String, Object> buildStoredMethodData(
        final String singleUseType,
        final Iterable<PluginProperty> properties,
        final RequestOptions requestOptions,
        final String customer_id
    ) {
        final Map<String, Object> data = new HashMap<>();
        // Always store the type so getSingleUseType(Map) can detect it later.
        data.put("type", singleUseType);
        data.put("object", "payment_method");
        data.put("customer_id", customer_id);
        data.put("livemode", isLiveMode(requestOptions.getApiKey()));
        data.put("created", System.currentTimeMillis() / 1000);

        if ("konbini".equals(singleUseType)) {
            // Required
            final String name  = PluginProperties.findPluginPropertyValue("fullname", properties);
            final String email = PluginProperties.findPluginPropertyValue("email", properties);
            final String store = PluginProperties.findPluginPropertyValue("store", properties);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("konbini payment method requires 'fullname'.");
            }
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("konbini payment method requires 'email'.");
            }
            if (store == null || store.isBlank()) {
                throw new IllegalArgumentException("konbini payment method requires 'store'.");
            }
            data.put("fullname", name);
            data.put("email", email);
            data.put("store", store);

            // Optional
            final String phone = PluginProperties.findPluginPropertyValue("phone", properties);
            // phone is optional for konbini
            if (phone != null && !phone.isBlank()) {
                data.put("phone", phone);
            }

        } else if ("bank_transfer".equals(singleUseType)) {
            // Required
            final String email = PluginProperties.findPluginPropertyValue("email", properties);
            if (email == null || email.isBlank()) {
                throw new IllegalArgumentException("bank_transfer payment method requires 'email'.");
            }
            data.put("email", email);

            // Optional
            final String name  = PluginProperties.findPluginPropertyValue("fullname", properties);
            if (name != null && !name.isBlank()) {
                data.put("fullname", name);
            }
        }

        return data;
    }

    /**
     * Determines if the Stripe API key belongs to a live account.
     */
    private static boolean isLiveMode(final String apiKey) {
        if (apiKey == null) {
            return false;
        }
        return apiKey.startsWith("sk_live_") || apiKey.startsWith("rk_live_") || apiKey.startsWith("pk_live_");
    }

    // -----------------------------------------------------------------------
    // purchasePayment() support — PaymentIntent creation
    // -----------------------------------------------------------------------

    /**
     * Build the payment_method_types list for a virtual PaymentIntent.
     *
     * Stripe uses its own naming: bank_transfer is called "customer_balance"
     * in the API. This method translates our internal type key to Stripe's name.
     *
     * @param singleUseType  Our internal type key.
     * @return               A list containing Stripe's payment method type string.
     */
    public static List<String> buildPaymentMethodTypes(final String singleUseType) {
        if ("bank_transfer".equals(singleUseType)) {
            return List.of(STRIPE_BANK_TRANSFER_TYPE);
        }
        return List.of(singleUseType); // "konbini" maps directly to "konbini" in Stripe
    }

    /**
     * Build the payment_method_data map for a virtual PaymentIntent.
     *
     * For virtual methods, we do not reference a stored Stripe PaymentMethod ID.
     * Instead we pass inline payment_method_data with the type and any required
     * customer details. This is added to the PaymentIntent params at creation time.
     *
     * For bank_transfer, Stripe's type name for this field is "customer_balance".
     *
     * @param singleUseType  Our internal type key.
     * @return               A map for the "payment_method_data" parameter.
     */
    public static Map<String, Object> buildPaymentMethodData(final String singleUseType,
                                                             final Map<String, Object> additionalData) {
        if ("konbini".equals(singleUseType)) {
            Map<String, Object> billingDetails = new HashMap<>();

            billingDetails.put("name",  additionalData.get("fullname"));
            billingDetails.put("email", additionalData.get("email"));

            if (additionalData.containsKey("phone")) {
                billingDetails.put("phone", additionalData.get("phone"));
            }

            return Map.of(
                "type", "konbini",
                "billing_details", billingDetails
            );
        }

        if ("bank_transfer".equals(singleUseType)) {
            // Bank transfer (customer_balance) is very minimal
            // email is useful but not strictly required by Stripe API
            Map<String, Object> data = new HashMap<>();
            data.put("type", "customer_balance");

            // Optional: you can still pass email if you have it
            if (additionalData.containsKey("email")) {
                data.put("email", additionalData.get("email"));
            }

            return data;
        }

        return Collections.emptyMap();
    }

    /**
     * Build the payment_method_options map for a virtual PaymentIntent.
     *
     * Each method type has its own options block in the PaymentIntent params.
     * The stored additionalData (from addPaymentMethod()) provides customer
     * details that must be included here.
     *
     * @param singleUseType  Our internal type key.
     * @param storedData     The additionalData map from the payment method DB record.
     *                       Contains name, email, phone stored at registration time.
     * @param description    The charge description from StripeConfigProperties.
     * @return               A map for the "payment_method_options" parameter.
     */
    public static Map<String, Object> buildPaymentMethodOptions(
        final String singleUseType,
        final Map<String, Object> storedData,
        final String description
    ) {
        if ("konbini".equals(singleUseType)) {
            // product_description is required for konbini. It appears on the
            // voucher shown at the convenience store terminal.
            return ImmutableMap.of(
                "konbini", ImmutableMap.of(
                    "product_description", description != null ? description : "Subscription payment",
                    "expires_after_days",  KONBINI_EXPIRES_AFTER_DAYS
                )
            );
        } else if ("bank_transfer".equals(singleUseType)) {
            // jp_bank_transfer uses Furikomi/Zengin rails.
            return ImmutableMap.of(
                "customer_balance", ImmutableMap.of(
                    "funding_type",   "bank_transfer",
                    "bank_transfer",  ImmutableMap.of("type", JP_BANK_TRANSFER_TYPE)
                )
            );
        }
        return ImmutableMap.of();
    }

    // -----------------------------------------------------------------------
    // purchasePayment() support — PaymentIntent confirmation
    // -----------------------------------------------------------------------

    /**
     * Confirm a newly created virtual PaymentIntent and return the confirmed
     * PaymentIntent with next_action details populated.
     *
     * virtual PaymentIntents must be created with confirm=false and then
     * explicitly confirmed. Confirmation is what triggers Stripe to generate the
     * konbini voucher code or bank transfer virtual account number, returned in
     * the PaymentIntent's next_action field.
     *
     * For Konbini: confirmation must include the customer's name in
     *   payment_method_data.konbini.customer_name (stored at addPaymentMethod time).
     *
     * For Bank Transfer: no extra data needed for confirmation.
     *
     * IMPORTANT: This method must be called immediately after PaymentIntent.create()
     * for virtual methods, before returning from executeInitialTransaction().
     * The confirmed intent is what gets stored in stripe_responses via dao.addResponse().
     * The next_action data in the confirmed intent is what the plugin returns to KillBill
     * as transaction properties, making voucher/bank details available to the caller.
     *
     * @param intent         The freshly created, unconfirmed PaymentIntent.
     *                       Status at this point is typically "requires_payment_method"
     *                       or "requires_confirmation".
     * @param singleUseType  Our internal type key.
     * @param storedData     The additionalData from the payment method record,
     *                       containing customer details stored at registration time.
     * @param requestOptions Stripe request options (API key, timeouts).
     * @return               The confirmed PaymentIntent. Status will be "requires_action"
     *                       if Stripe successfully generated the payment instructions,
     *                       or "succeeded" if payment was already available (rare).
     * @throws StripeException if the Stripe confirmation call fails.
     */
    public static PaymentIntent confirmIntent(
        final PaymentIntent intent,
        final String singleUseType,
        final Map<String, Object> storedData,
        final RequestOptions requestOptions
    ) throws StripeException {

        // Skip confirmation if already confirmed or succeeded
        if ("succeeded".equals(intent.getStatus()) || 
            "processing".equals(intent.getStatus()) ||
            "requires_action".equals(intent.getStatus())) {
            logger.info("[StripeVirtualPaymentMethods] PaymentIntent {} already in status {}, skipping confirmation",
                        intent.getId(), intent.getStatus());
            return intent;
        }

        final Map<String, Object> confirmParams = new HashMap<>();

        if ("konbini".equals(singleUseType)) {
            // Customer name is required at confirmation time for konbini.
            // It appears on the payment receipt at the convenience store.
            final String customerName = storedData != null
                ? (String) storedData.get("fullname")
                : null;

            if (customerName == null || customerName.isBlank()) {
                throw new IllegalStateException(
                    "Konbini PaymentIntent confirmation requires customer fullname, " +
                    "but none was found in the stored payment method data. " +
                    "Ensure 'fullname' was passed when registering the konbini payment method."
                );
            }

            // Konbini requires customer name AND email in payment_method_data at confirmation.
            final String customerEmail = storedData != null ? (String) storedData.get("email") : null;
            if (customerEmail == null || customerEmail.isBlank()) {
                throw new IllegalStateException("Konbini PaymentIntent confirmation requires customer email.");
            }

            confirmParams.put("payment_method_data", ImmutableMap.of(
                "type", "konbini",
                "billing_details", ImmutableMap.of(
                    "name", customerName,
                    "email", customerEmail
                )
            ));

        } else if ("bank_transfer".equals(singleUseType)) {
            // Bank transfer confirmation does not require additional customer data —
            // Stripe generates the virtual account from the customer object on the intent.
            // No extra params needed.
        } else {
            // Defensive check: this method should only be called for known virtual types
            logger.warn("[StripeVirtualPaymentMethods] confirmIntent called with unexpected type: {}", singleUseType);
        }

        logger.info("[StripeVirtualPaymentMethods] Confirming {} PaymentIntent {}", singleUseType, intent.getId());
        final PaymentIntent confirmed = intent.confirm(confirmParams, requestOptions);
        logger.info("[StripeVirtualPaymentMethods] Confirmed intent {} status={} has_next_action={}",
                    confirmed.getId(), confirmed.getStatus(), confirmed.getNextAction() != null);
        
        // Validate that next_action was populated for virtual methods
        if (confirmed.getNextAction() == null && "requires_action".equals(confirmed.getStatus())) {
            logger.warn("[StripeVirtualPaymentMethods] Confirmed {} intent {} has status requires_action but no next_action data",
                        singleUseType, confirmed.getId());
        }
        
        return confirmed;
    }

    // -----------------------------------------------------------------------
    // getPaymentInfo() support — next_action extraction
    // -----------------------------------------------------------------------

    /**
     * Extract the payment instruction details from a confirmed PaymentIntent's
     * next_action field, for storage and return to the caller.
     *
     * These details are what the customer needs to complete the payment:
     *   Konbini:       confirmation number, store, expiry date
     *   Bank transfer: bank name, account number, branch, reference, expiry
     *
     * The returned map is merged into the transaction's additional properties
     * via dao.addResponse(), making them available to callers of getPaymentInfo().
     *
     * If no next_action is present (e.g. the intent was already succeeded),
     * returns an empty map — this is safe; the caller should not fail.
     *
     * @param intent         A confirmed PaymentIntent. May have null nextAction
     *                       if the intent was created in a terminal state.
     * @param singleUseType  Our internal type key.
     * @return               A flat map of key→value strings for storage.
     */
    public static Map<String, Object> extractNextActionDetails(
            final PaymentIntent intent,
            final String singleUseType) {

        final Map<String, Object> details = new HashMap<>();

        if (intent == null || intent.getNextAction() == null) {
            return details;
        }

        final PaymentIntent.NextAction nextAction = intent.getNextAction();

        if ("konbini".equals(singleUseType) && 
            "konbini_display_details".equals(nextAction.getType())) {

            final PaymentIntent.NextAction.KonbiniDisplayDetails konbini = 
                nextAction.getKonbiniDisplayDetails();

            if (konbini != null) {
                // Try different possible getter names (library version differences)
                details.put("konbini_confirmation_number", 
                    getFieldSafely(konbini, "confirmationNumber", "confirmation_number"));

                details.put("konbini_expires_at", konbini.getExpiresAt());
                details.put("konbini_hosted_voucher_url", konbini.getHostedVoucherUrl());

                if (konbini.getStores() != null) {
                    details.put("konbini_stores", konbini.getStores());
                }
            }
        } 
        else if ("bank_transfer".equals(singleUseType) && 
                 "display_bank_transfer_instructions".equals(nextAction.getType())) {

            final PaymentIntent.NextAction.DisplayBankTransferInstructions bt = 
                nextAction.getDisplayBankTransferInstructions();

            if (bt != null) {
                details.put("bank_transfer_reference", bt.getReference());
                details.put("bank_transfer_amount_remaining", bt.getAmountRemaining());
                details.put("bank_transfer_currency", bt.getCurrency());
                details.put("bank_transfer_hosted_instructions_url", bt.getHostedInstructionsUrl());

                if (bt.getFinancialAddresses() != null) {
                    details.put("bank_transfer_financial_addresses", bt.getFinancialAddresses());
                }
            }
        }

        return details;
    }

    // Helper to safely extract fields that may have different getter names
    private static Object getFieldSafely(Object obj, String camelCase, String snakeCase) {
        try {
            // Try camelCase first (most common in newer SDK)
            java.lang.reflect.Method m = obj.getClass().getMethod("get" + camelCase.substring(0,1).toUpperCase() + camelCase.substring(1));
            return m.invoke(obj);
        } catch (Exception ignored) {}
        try {
            // Try snake_case style if available
            java.lang.reflect.Method m = obj.getClass().getMethod("get" + snakeCase);
            return m.invoke(obj);
        } catch (Exception ignored) {}
        return null;
    }

    // -----------------------------------------------------------------------
    // getPaymentInfo() / Janitor support
    // -----------------------------------------------------------------------

    /**
     * Return true if the given PaymentIntent status string indicates the payment
     * is genuinely waiting for customer action on a virtual method — as opposed
     * to waiting for 3DS (which also uses "requires_action" but is handled differently).
     *
     * For virtual methods, "requires_action" means: awaiting customer payment
     * (konbini: waiting for store visit; bank_transfer: waiting for transfer arrival).
     * These should return PENDING to the Janitor so it keeps polling.
     *
     * This is used in getPaymentInfo() to avoid the 3DS confirmation path for
     * virtual intents that happen to also use "requires_action" status.
     *
     * @param status         The PaymentIntent status string from Stripe.
     * @param singleUseType  Our internal type key, may be null for non-virtual.
     * @return               true if this is a legitimate awaiting-customer state.
     */
    public static boolean isAwaitingCustomerAction(final String status, final String singleUseType) {
        return requiresSpecialHandling(singleUseType) && "requires_action".equals(status);
    }
}