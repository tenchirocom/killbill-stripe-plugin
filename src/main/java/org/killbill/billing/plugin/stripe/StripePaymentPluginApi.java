/*
 * Copyright 2020-2020 Equinix, Inc
 * Copyright 2014-2020 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
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
 *
 * ======================================================================
 * Significant modifications were subsequently made by Tenchiro LLC to
 * support single-use payments such as konbini and bank_transfers for the
 * Japan market.
 *
 * Copyright 2026 Tenchiro LLC
 *
 * All modifications made by Tenchiro LLC are also licensed under the
 * Apache License, Version 2.0.
 */
package org.killbill.billing.plugin.stripe;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.osgi.libs.killbill.OSGIConfigPropertiesService;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentMethodPlugin;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.GatewayNotification;
import org.killbill.billing.payment.plugin.api.HostedPaymentPageFormDescriptor;
import org.killbill.billing.payment.plugin.api.PaymentMethodInfoPlugin;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.plugin.api.core.PluginCustomField;
import org.killbill.billing.plugin.api.payment.PluginHostedPaymentPageFormDescriptor;
import org.killbill.billing.plugin.api.payment.PluginPaymentPluginApi;
import org.killbill.billing.plugin.api.payment.PluginGatewayNotification;
import org.killbill.billing.plugin.stripe.dao.StripeDao;
import org.killbill.billing.plugin.stripe.dao.gen.tables.StripePaymentMethods;
import org.killbill.billing.plugin.stripe.dao.gen.tables.StripeResponses;
import org.killbill.billing.plugin.stripe.dao.gen.tables.records.StripeHppRequestsRecord;
import org.killbill.billing.plugin.stripe.dao.gen.tables.records.StripePaymentMethodsRecord;
import org.killbill.billing.plugin.stripe.dao.gen.tables.records.StripeResponsesRecord;
import org.killbill.billing.plugin.util.KillBillMoney;
import org.killbill.billing.util.api.CustomFieldApiException;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
import org.killbill.clock.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.BankAccount;
import com.stripe.model.Card;
import com.stripe.model.Charge;
import com.stripe.model.ChargeSearchResult;
import com.stripe.model.Customer;
import com.stripe.model.HasId;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentSource;
import com.stripe.model.PaymentSourceCollection;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.model.Refund;
import com.stripe.model.SetupIntent;
import com.stripe.model.Source;
import com.stripe.model.Token;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.ChargeSearchParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.model.Event;
import com.stripe.net.Webhook;

public class StripePaymentPluginApi extends PluginPaymentPluginApi<StripeResponsesRecord, StripeResponses, StripePaymentMethodsRecord, StripePaymentMethods> {

    private enum CaptureMethod {
        AUTOMATIC("automatic"),
        MANUAL("manual");

        public final String value;

        CaptureMethod(final String value) {
            this.value = value;
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(StripePaymentPluginApi.class);

    public static final String PROPERTY_FROM_HPP = "fromHPP";
    public static final String PROPERTY_HPP_COMPLETION = "fromHPPCompletion";
    public static final String PROPERTY_OVERRIDDEN_TRANSACTION_STATUS = "overriddenTransactionStatus";

    private final StripeConfigPropertiesConfigurationHandler stripeConfigPropertiesConfigurationHandler;
    private final StripeDao dao;

    static final List<String> metadataFilter = List.of("payment_method_types");

    private final Map<String, Object> expandSourcesParams;

    public StripePaymentPluginApi(final StripeConfigPropertiesConfigurationHandler stripeConfigPropertiesConfigurationHandler,
                                  final OSGIKillbillAPI killbillAPI,
                                  final OSGIConfigPropertiesService configProperties,
                                  final Clock clock,
                                  final StripeDao dao) {
        super(killbillAPI, configProperties, clock, dao);
        this.stripeConfigPropertiesConfigurationHandler = stripeConfigPropertiesConfigurationHandler;
        this.dao = dao;
        expandSourcesParams = new HashMap<>();
        expandSourcesParams.put("expand", List.of("sources"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Infer the single-use type from a live or stored PaymentIntent by inspecting
     * its payment_method_types list. This is the only reliable way to detect the
     * type in getPaymentInfo(), where we have the intent but not the PM record.
     *
     * "customer_balance" is Stripe's name for our "bank_transfer" type.
     */
    private String getSingleUseTypeFromIntent(final PaymentIntent intent) {
        final List<String> types = intent.getPaymentMethodTypes();
        if (types != null) {
            if (types.contains("konbini"))          return "konbini";
            if (types.contains("customer_balance")) return "bank_transfer";
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // getPaymentInfo — Janitor polling entry point
    // -------------------------------------------------------------------------

    @Override
    public List<PaymentTransactionInfoPlugin> getPaymentInfo(final UUID kbAccountId,
                                                             final UUID kbPaymentId,
                                                             final Iterable<PluginProperty> properties,
                                                             final TenantContext context) throws PaymentPluginApiException {
        final List<PaymentTransactionInfoPlugin> transactions = super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context);
        if (transactions.isEmpty()) {
            return transactions;
        }

        // Check if a HPP payment needs to be canceled by the expiry policy
        final ExpiredPaymentPolicy expiredPaymentPolicy = new ExpiredPaymentPolicy(
                clock, stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId()));
        final StripePaymentTransactionInfoPlugin transactionToExpire = expiredPaymentPolicy.isExpired(transactions);
        if (transactionToExpire != null) {
            logger.info("Canceling expired Stripe transaction {} (created {})",
                        transactionToExpire.getStripeResponseRecord().getStripeId(),
                        transactionToExpire.getStripeResponseRecord().getCreatedDate());
            final Map<String, String> additionalMetadata = Map.of(
                    PROPERTY_OVERRIDDEN_TRANSACTION_STATUS, PaymentPluginStatus.CANCELED.toString(),
                    "message", "Payment Expired - Cancelled by Janitor");
            try {
                dao.updateResponse(transactionToExpire.getStripeResponseRecord(), additionalMetadata);
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Unable to update expired payment", e);
            }
            return super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context);
        }

        // Refresh PENDING and UNDEFINED transactions
        boolean wasRefreshed = false;
        final RequestOptions requestOptions = buildRequestOptions(context);

        for (final PaymentTransactionInfoPlugin transaction : transactions) {

            if (transaction.getStatus() == PaymentPluginStatus.PENDING) {

                final String paymentIntentId = PluginProperties.findPluginPropertyValue("id", transaction.getProperties());
                try {
                    // Retrieve the current intent from Stripe
                    PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId, requestOptions);

                    // Detect whether this is a single-use method (konbini / bank_transfer).
                    // We read from the live intent's payment_method_types because the payment
                    // method record is not easily available here.
                    final String singleUseType = getSingleUseTypeFromIntent(intent);

                    if (StripeMethodExtensions.isAwaitingCustomerAction(intent.getStatus(), singleUseType)) {
                        // Single-use PENDING = legitimately waiting for the customer to pay
                        // at the store or complete the bank transfer. Do NOT treat this as a
                        // 3DS flow — do not confirm, do not cancel. Just refresh the stored
                        // record so the DB stays current and continue.
                        logger.debug("Single-use payment {} is awaiting customer action (status={}). Skipping 3DS path.",
                                     intent.getId(), intent.getStatus());
                        final Charge lastCharge = getLastCharge(intent, Collections.emptyMap(), requestOptions);
                        dao.updateResponse(transaction.getKbTransactionPaymentId(), intent, lastCharge, context.getTenantId());
                        wasRefreshed = true;
                        continue;
                    }

                    // Standard card / 3DS path below this point.

                    // 3DS validated: must confirm the PaymentIntent
                    if ("requires_confirmation".equals(intent.getStatus())) {
                        logger.info("Confirming Stripe transaction {}", intent.getId());
                        intent = intent.confirm(requestOptions);
                    }
                    // 3DS authorization failure — cancel if configured to do so
                    else if (stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId()).isCancelOn3DSAuthorizationFailure()
                            && "requires_payment_method".equals(intent.getStatus())
                            && intent.getLastPaymentError() != null
                            && "payment_intent_authentication_failure".equals(intent.getLastPaymentError().getCode())) {
                        logger.info("Cancelling Stripe PaymentIntent after 3DS authorization failure {}", intent.getId());
                        intent = intent.cancel(
                                PaymentIntentCancelParams.builder()
                                        .setCancellationReason(PaymentIntentCancelParams.CancellationReason.ABANDONED)
                                        .build(),
                                requestOptions);
                    }

                    final Charge lastCharge = getLastCharge(intent, Collections.emptyMap(), requestOptions);
                    if (lastCharge != null) {
                        dao.updateResponse(transaction.getKbTransactionPaymentId(), intent, lastCharge, context.getTenantId());
                        wasRefreshed = true;
                    }
                } catch (final StripeException e) {
                    logger.warn("Unable to fetch latest payment state in Stripe, data might be stale", e);
                } catch (final SQLException e) {
                    throw new PaymentPluginApiException("Unable to refresh payment", e);
                }

            } else if (transaction.getStatus() == PaymentPluginStatus.UNDEFINED) {

                final ChargeSearchParams searchParams = ChargeSearchParams.builder()
                        .setQuery("metadata['kbTransactionId']:'" + transaction.getKbTransactionPaymentId() + "'")
                        .build();
                try {
                    final ChargeSearchResult result = Charge.search(searchParams, requestOptions);
                    if (result.getData().size() == 1) {
                        final Charge charge = result.getData().get(0);
                        if (charge.getPaymentIntent() != null) {
                            final PaymentIntent intent = PaymentIntent.retrieve(charge.getPaymentIntent(), requestOptions);
                            logger.info("Fixing Stripe transaction {}", intent.getId());
                            final Charge lastCharge = getLastCharge(intent, Collections.emptyMap(), requestOptions);
                            if (lastCharge != null) {
                                dao.updateResponse(transaction.getKbTransactionPaymentId(), intent, lastCharge, context.getTenantId());
                                wasRefreshed = true;
                            }
                        }
                    } else if (result.getData().isEmpty()) {
                        logger.info("Canceling UNKNOWN Stripe transaction for kbTransactionId={}", transaction.getKbTransactionPaymentId());
                        final Map<String, Object> additionalMetadata = ImmutableMap.<String, Object>builder()
                                .put(PROPERTY_OVERRIDDEN_TRANSACTION_STATUS, PaymentPluginStatus.CANCELED.toString())
                                .put("message", "Payment didn't happen - Cancelled by Janitor")
                                .build();
                        dao.updateResponse(transaction.getKbTransactionPaymentId(), additionalMetadata, context.getTenantId());
                        wasRefreshed = true;
                    }
                } catch (final StripeException e) {
                    logger.warn("Unable to fetch latest payment state in Stripe, data might be stale", e);
                } catch (final SQLException e) {
                    throw new PaymentPluginApiException("Unable to refresh payment", e);
                }
            }
        }

        return wasRefreshed ? super.getPaymentInfo(kbAccountId, kbPaymentId, properties, context) : transactions;
    }

    @Override
    protected PaymentTransactionInfoPlugin buildPaymentTransactionInfoPlugin(final StripeResponsesRecord record) {
        return StripePaymentTransactionInfoPlugin.build(record);
    }

    @Override
    public PaymentMethodPlugin getPaymentMethodDetail(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                                      final Iterable<PluginProperty> properties,
                                                      final TenantContext context) throws PaymentPluginApiException {
        final StripePaymentMethodsRecord record;
        try {
            record = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve payment method for kbPaymentMethodId " + kbPaymentMethodId, e);
        }

        if (record == null) {
            return new StripePaymentMethodPlugin(kbPaymentMethodId, null, false, ImmutableList.of());
        } else {
            return buildPaymentMethodPlugin(record);
        }
    }

    @Override
    protected PaymentMethodPlugin buildPaymentMethodPlugin(final StripePaymentMethodsRecord record) {
        return StripePaymentMethodPlugin.build(record);
    }

    @Override
    protected PaymentMethodInfoPlugin buildPaymentMethodInfoPlugin(final StripePaymentMethodsRecord record) {
        return StripePaymentMethodInfoPlugin.build(record);
    }

    // -------------------------------------------------------------------------
    // addPaymentMethod
    // -------------------------------------------------------------------------

    @Override
    public void addPaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                 final PaymentMethodPlugin paymentMethodProps, final boolean setDefault,
                                 final Iterable<PluginProperty> properties,
                                 final CallContext context) throws PaymentPluginApiException {

        final RequestOptions requestOptions = buildRequestOptions(context);
        final Iterable<PluginProperty> allProperties = PluginProperties.merge(paymentMethodProps.getProperties(), properties);

        // Check for Single-use method
        final String singleUseType = StripeMethodExtensions.getSingleUseType(allProperties);

        // ── Single-use payment methods (i.e. konbini, bank_transfer) ──────────────
        // These have no real Stripe PaymentMethod ID. The payment methods are created on the fly
        // for specific payments. However, the customer details (i.e. name, email, phone, ...) are
        // stored in additional_data and a sentinel stripe_id is generated so the method can be
        // uniquely identified and distinguished from real Stripe object.

        if (singleUseType != null) {
            //
            // Add Single-Use Pseudo Payment Method
            //
            logger.info("Registering single-use payment method '{}' for kbPaymentMethodId={}", singleUseType, kbPaymentMethodId);

            //String existingCustomerId = getCustomerIdNoException(kbAccountId, context);
            //final String customerId = ensureStripeCustomer(kbAccountId, context, requestOptions, allProperties);

            // Ensure a Stripe Customer exists for ALL single-use types.
            // konbini does not strictly require a customer at the Stripe API level, but
            // creating one here guarantees consistent account linkage and means
            // buildStoredMethodData always receives a valid customerId rather than null.
            // bank_transfer requires a customer — Stripe rejects the PaymentIntent without one.

            String existingCustomerId = getCustomerIdNoException(kbAccountId, context);
            if (existingCustomerId == null) {
                try {
                    // Create a new Stripe customer, there is no existing customer id
                    final Customer newCustomer = createStripeCustomer(
                        kbAccountId,
                        existingCustomerId,
                        ImmutableMap.of(),
                        requestOptions,
                        allProperties,
                        context
                    );
                    if (newCustomer != null) {
                        existingCustomerId = newCustomer.getId();
                    }
                } catch (final StripeException e) {
                    throw new PaymentPluginApiException("Failed to create or retrieve Stripe customer for account " + kbAccountId, e);
                }
            }

            final Map<String, Object> additionalData;
            try {
                additionalData = StripeMethodExtensions.buildStoredMethodData(singleUseType, allProperties, requestOptions, existingCustomerId);
            } catch (final IllegalArgumentException e) {
                throw new PaymentPluginApiException("USER", e.getMessage());
            }
            try {
                dao.addPaymentMethod(kbAccountId, kbPaymentMethodId, additionalData,
                                     StripeMethodExtensions.buildSentinelStripeId(singleUseType),
                                     clock.getUTCNow(), context.getTenantId());
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Failed to save single-use payment method", e);
            }
            return; // Do not fall through to standard Stripe PM creation
        }

        // ── Standard Stripe payment methods (card, token, source, bank_account) ─

        String paymentMethodIdInStripe = paymentMethodProps.getExternalPaymentMethodId();
        String objectType = PluginProperties.getValue("object", "payment_method", allProperties);
        if (paymentMethodIdInStripe == null) {
            paymentMethodIdInStripe = PluginProperties.findPluginPropertyValue("source", allProperties);
            if (paymentMethodIdInStripe != null) {
                objectType = "source";
            } else {
                paymentMethodIdInStripe = PluginProperties.findPluginPropertyValue("token", allProperties);
                if (paymentMethodIdInStripe != null) {
                    objectType = "token";
                }
            }
        }

        final String sessionId = PluginProperties.findPluginPropertyValue("sessionId", allProperties);
        if (sessionId != null) {
            try {
                final StripeHppRequestsRecord hppRecord = dao.getHppRequest(sessionId, context.getTenantId().toString());
                if (hppRecord == null) {
                    throw new PaymentPluginApiException("INTERNAL", "Unable to add payment method: missing StripeHppRequestsRecord for sessionId " + sessionId);
                }
                final String setupIntentId = (String) StripeDao.fromAdditionalData(hppRecord.getAdditionalData()).get("setup_intent_id");
                final SetupIntent setupIntent = SetupIntent.retrieve(setupIntentId, requestOptions);
                if ("succeeded".equals(setupIntent.getStatus())) {
                    final String existingCustomerId = getCustomerIdNoException(kbAccountId, context);
                    if (existingCustomerId == null) {
                        logger.info("Mapping kbAccountId {} to Stripe customer {}", kbAccountId, setupIntent.getCustomer());
                        killbillAPI.getCustomFieldUserApi().addCustomFields(
                                ImmutableList.of(new PluginCustomField(kbAccountId, ObjectType.ACCOUNT,
                                                                       "STRIPE_CUSTOMER_ID", setupIntent.getCustomer(),
                                                                       clock.getUTCNow())), context);
                    } else if (!existingCustomerId.equals(setupIntent.getCustomer())) {
                        throw new PaymentPluginApiException("USER", "Unable to add payment method: setupIntent customerId is "
                                + setupIntent.getCustomer() + " but account already mapped to " + existingCustomerId);
                    }
                    paymentMethodIdInStripe = setupIntent.getPaymentMethod();
                } else {
                    throw new PaymentPluginApiException("EXTERNAL", "Unable to add payment method: setupIntent status is: " + setupIntent.getStatus());
                }
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Unable to add payment method", e);
            } catch (final CustomFieldApiException e) {
                throw new PaymentPluginApiException("Unable to add custom field", e);
            } catch (final StripeException e) {
                throw new PaymentPluginApiException("Error calling Stripe while adding payment method", e);
            }
        }

        final Map<String, Object> additionalDataMap;
        final String stripeId;
        final String existingCustomerId = getCustomerIdNoException(kbAccountId, context);

        if (paymentMethodIdInStripe != null) {
            if ("payment_method".equals(objectType)) {
                //
                // Add a payment_method method. PaymentMethod (pm_xxx) is Stripe's modern,
                // recommended API for representing payment instruments. Handles cards, bank
                // accounts, wallets, and other payment methods under one consistent API
                //
                try {
                    final PaymentMethod stripePaymentMethod = PaymentMethod.retrieve(paymentMethodIdInStripe, requestOptions);
                    final PaymentMethod paymentMethodForAdditionalData;
                    if (existingCustomerId == null) {
                        createStripeCustomer(
                            kbAccountId, null,
                            ImmutableMap.of("payment_method", stripePaymentMethod.getId()),
                            requestOptions,
                            allProperties,
                            context
                        );
                        paymentMethodForAdditionalData = stripePaymentMethod;
                    } else {
                        paymentMethodForAdditionalData = stripePaymentMethod.attach(
                                ImmutableMap.of("customer", existingCustomerId), requestOptions);
                    }
                    additionalDataMap = StripePluginProperties.toAdditionalDataMap(paymentMethodForAdditionalData);
                    stripeId = paymentMethodForAdditionalData.getId();
                } catch (final StripeException e) {
                    throw new PaymentPluginApiException("Error calling Stripe while adding payment method", e);
                }
            } else if ("token".equals(objectType)) {
                //
                // Add a token payment method. Token (tok_xxx) is a temporary, single-use object
                // that securely represents card details collected on the client side.
                // Legacy API: Still supported for cards, but Stripe recommends using PaymentMethods
                // with SetupIntents instead
                //
                try {
                    final Token stripeToken = Token.retrieve(paymentMethodIdInStripe, requestOptions);

                    if (existingCustomerId == null) {
                        // Create new customer
                        final Customer newCustomer = createStripeCustomer(
                            kbAccountId, null,
                            ImmutableMap.of("source", stripeToken.getId()),
                            requestOptions,
                            allProperties,
                            context
                        );
                        final Customer customerWithSources = Customer.retrieve(newCustomer.getId(), expandSourcesParams, requestOptions);
                        
                        final String cardId = customerWithSources.getDefaultSource();
                        final PaymentSource resultingCard = customerWithSources.getSources().getData()
                            .stream()
                            .filter(s -> s.getId().equals(cardId))
                            .findFirst()
                            .orElse(null);
                        
                        // Use helper method
                        final PaymentMethodResult result = retrievePaymentMethodForCard(
                            newCustomer.getId(),
                            cardId,
                            resultingCard,
                            requestOptions
                        );
                        stripeId = result.stripeId;
                        additionalDataMap = result.additionalDataMap;
                    } else {
                        // Existing customer
                        final Customer customer = Customer.retrieve(existingCustomerId, expandSourcesParams, requestOptions);
                        final Map<String, Object> attachParams = new HashMap<>();
                        attachParams.put("source", stripeToken.getId());
                        
                        final PaymentSource attachedSource = customer.getSources().create(attachParams, requestOptions);
                        final String cardId = attachedSource.getId();
                        
                        // Use helper method
                        final PaymentMethodResult result = retrievePaymentMethodForCard(
                            existingCustomerId,
                            cardId,
                            attachedSource,
                            requestOptions
                        );
                        stripeId = result.stripeId;
                        additionalDataMap = result.additionalDataMap;

                        if (setDefault) {
                            customer.update(ImmutableMap.of("default_source", cardId), requestOptions);
                        }
                    }
                } catch (final StripeException e) {
                    throw new PaymentPluginApiException("Error calling Stripe while adding payment method", e);
                }
            } else if ("source".equals(objectType)) {
                //
                // Add a source payment method type. Source (src_xxx) is a flexible, reusable object that
                // represents various payment methods beyond just cards. Legacy API: Still supported, but
                // Stripe recommends using PaymentMethods for most use cases
                //
                try {
                    final Source stripeSource = Source.retrieve(paymentMethodIdInStripe, requestOptions);
                    final String customerId;
                    final PaymentSource attachedSource;
                    
                    if (existingCustomerId == null) {
                        final Customer newCustomer = createStripeCustomer(
                            kbAccountId, null,
                            ImmutableMap.of("source", stripeSource.getId()),
                            requestOptions,
                            allProperties,
                            context
                        );
                        customerId = newCustomer.getId();
                        
                        final Customer customerWithSources = Customer.retrieve(
                            customerId, expandSourcesParams, requestOptions);
                        attachedSource = customerWithSources.getSources().getData()
                            .stream()
                            .filter(s -> s.getId().equals(stripeSource.getId()))
                            .findFirst()
                            .orElse(stripeSource);
                    } else {
                        customerId = existingCustomerId;
                        final Customer customer = Customer.retrieve(existingCustomerId, expandSourcesParams, requestOptions);
                        final Map<String, Object> attachParams = new HashMap<>();
                        attachParams.put("source", stripeSource.getId());
                        attachedSource = customer.getSources().create(attachParams, requestOptions);
                    }
                    
                    // If it's a card source, try to get the PaymentMethod for complete data
                    if ("card".equals(stripeSource.getType()) && attachedSource instanceof Card) {
                        final PaymentMethodResult result = retrievePaymentMethodForCard(
                            customerId,
                            attachedSource.getId(),
                            attachedSource,
                            requestOptions
                        );
                        stripeId = result.stripeId;
                        additionalDataMap = result.additionalDataMap;
                    } else {
                        // Non-card source - use source data directly
                        stripeId = attachedSource.getId();
                        additionalDataMap = StripePluginProperties.toAdditionalDataMap(attachedSource);
                    }
                } catch (final StripeException e) {
                    throw new PaymentPluginApiException("Error calling Stripe while adding payment method", e);
                }
            } else if ("bank_account".equals(objectType)) {
                //
                // Add a bank_account payment method. Bank Account (btok_xxx token → ba_xxx attached) is
                // a legacy object for representing bank account payment methods. Legacy API: Predates
                // PaymentMethods, but still widely used for ACH payments. May have PaymentMethod. Modern
                // ACH Direct Debit creates corresponding pm_xxx objects.
                //
                try {
                    final String customerId;
                    final PaymentSource attachedBankAccount;
                    
                    if (existingCustomerId == null) {
                        // Create new customer with bank account token
                        final Customer newCustomer = createStripeCustomer(
                            kbAccountId, null,
                            ImmutableMap.of("source", paymentMethodIdInStripe), // btok_xxx
                            requestOptions,
                            allProperties,
                            context
                        );
                        customerId = newCustomer.getId();
                        
                        // Retrieve customer with expanded sources to get the attached bank account
                        final Customer customerWithSources = Customer.retrieve(
                            customerId, expandSourcesParams, requestOptions);
                        attachedBankAccount = customerWithSources.getSources().getData()
                            .stream()
                            .filter(s -> s instanceof BankAccount)
                            .findFirst()
                            .orElse(null);
                    } else {
                        // Attach bank account token to existing customer
                        customerId = existingCustomerId;
                        final Customer customer = Customer.retrieve(
                            existingCustomerId, expandSourcesParams, requestOptions);
                        final Map<String, Object> attachParams = new HashMap<>();
                        attachParams.put("source", paymentMethodIdInStripe); // btok_xxx
                        attachedBankAccount = customer.getSources().create(attachParams, requestOptions);
                    }
                    
                    // Try to find corresponding PaymentMethod for complete data (ACH Direct Debit)
                    if (attachedBankAccount instanceof BankAccount) {
                        final PaymentMethodResult result = retrievePaymentMethodForBankAccount(
                            customerId,
                            attachedBankAccount.getId(),
                            attachedBankAccount,
                            requestOptions
                        );
                        stripeId = result.stripeId;
                        additionalDataMap = result.additionalDataMap;
                    } else {
                        stripeId = attachedBankAccount.getId();
                        additionalDataMap = StripePluginProperties.toAdditionalDataMap(attachedBankAccount);
                    }
                } catch (final StripeException e) {
                    throw new PaymentPluginApiException("Error calling Stripe while adding payment method", e);
                }
            } else {
                throw new UnsupportedOperationException("Payment Method type not yet supported: " + objectType);
            }
        } else {
            throw new PaymentPluginApiException(
                "USER",
                "PaymentMethodPlugin#getExternalPaymentMethodId or sessionId plugin property must be passed"
            );
        }

        try {
            dao.addPaymentMethod(
                kbAccountId,
                kbPaymentMethodId,
                additionalDataMap,
                stripeId,
                clock.getUTCNow(),
                context.getTenantId()
            );
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to add payment method", e);
        }
    }

    /**
     * Retrieves the PaymentMethod associated with a card source by fingerprint matching.
     * Returns the PaymentMethod ID and additionalDataMap.
     */
    private PaymentMethodResult retrievePaymentMethodForCard(
            final String customerId,
            final String cardId,
            final PaymentSource cardSource,
            final RequestOptions requestOptions) throws StripeException {
        
        // List PaymentMethods to find the one matching this card
        final Map<String, Object> pmListParams = new HashMap<>();
        pmListParams.put("customer", customerId);
        pmListParams.put("type", "card");
        
        final PaymentMethodCollection paymentMethods = PaymentMethod.list(pmListParams, requestOptions);
        
        // Find the PaymentMethod matching this card by fingerprint
        final String cardFingerprint = ((com.stripe.model.Card) cardSource).getFingerprint();
        final PaymentMethod matchingPM = paymentMethods.getData().stream()
            .filter(pm -> pm.getCard() != null && 
                        pm.getCard().getFingerprint().equals(cardFingerprint))
            .findFirst()
            .orElse(null);
        
        if (matchingPM != null) {
            // Use PaymentMethod for complete data
            return new PaymentMethodResult(
                matchingPM.getId(),
                StripePluginProperties.toAdditionalDataMap(matchingPM)
            );
        } else {
            // Fallback: use card source data (incomplete)
            return new PaymentMethodResult(
                cardId,
                StripePluginProperties.toAdditionalDataMap(cardSource)
            );
        }
    }

    private PaymentMethodResult retrievePaymentMethodForBankAccount(
            final String customerId,
            final String bankAccountId,
            final PaymentSource bankAccountSource,
            final RequestOptions requestOptions
    ) throws StripeException
    {    
        // List PaymentMethods of type us_bank_account
        final Map<String, Object> pmListParams = new HashMap<>();
        pmListParams.put("customer", customerId);
        pmListParams.put("type", "us_bank_account");
        
        final com.stripe.model.PaymentMethodCollection paymentMethods = 
            PaymentMethod.list(pmListParams, requestOptions);
        
        // Find the PaymentMethod matching this bank account by last4 and routing number
        final BankAccount bankAccount = (BankAccount) bankAccountSource;
        final PaymentMethod matchingPM = paymentMethods.getData().stream()
            .filter(pm -> pm.getUsBankAccount() != null && 
                        pm.getUsBankAccount().getLast4().equals(bankAccount.getLast4()) &&
                        pm.getUsBankAccount().getRoutingNumber().equals(bankAccount.getRoutingNumber()))
            .findFirst()
            .orElse(null);
        
        if (matchingPM != null) {
            return new PaymentMethodResult(
                matchingPM.getId(),
                StripePluginProperties.toAdditionalDataMap(matchingPM)
            );
        } else {
            // Fallback: use bank account source data
            return new PaymentMethodResult(
                bankAccountId,
                StripePluginProperties.toAdditionalDataMap(bankAccountSource)
            );
        }
    }

    // Simple result holder class
    private static class PaymentMethodResult {
        final String stripeId;
        final Map<String, Object> additionalDataMap;
        
        PaymentMethodResult(String stripeId, Map<String, Object> additionalDataMap) {
            this.stripeId = stripeId;
            this.additionalDataMap = additionalDataMap;
        }
    }

    private String getTokenInnerId(final Token token) {
        switch (token.getType()) {
            case "card":         return token.getCard().getId();
            case "bank_account": return token.getBankAccount().getId();
            default:             return token.getId();
        }
    }

    private Customer createStripeCustomer(final UUID kbAccountId,
                                        final String existingCustomerId,
                                        final ImmutableMap<String, Object> customerParams,
                                        final RequestOptions requestOptions,
                                        final Iterable<PluginProperty> allProperties,
                                        final CallContext context) throws StripeException, PaymentPluginApiException {
        final String createStripeCustomerProperty = PluginProperties.findPluginPropertyValue("createStripeCustomer", allProperties);
        if (existingCustomerId == null && (createStripeCustomerProperty == null || Boolean.parseBoolean(createStripeCustomerProperty))) {
            final Account account = getAccount(kbAccountId, context);
            final Map<String, Object> address = new HashMap<>();
            address.put("city",        account.getCity());
            address.put("country",     account.getCountry());
            address.put("line1",       account.getAddress1());
            address.put("line2",       account.getAddress2());
            address.put("postal_code", account.getPostalCode());
            address.put("state",       account.getStateOrProvince());

            final Map<String, Object> params = new HashMap<>(customerParams);
            params.put("metadata",    ImmutableMap.of("kbAccountId", kbAccountId, "kbAccountExternalKey", account.getExternalKey()));
            params.put("email",       account.getEmail());
            params.put("name",        account.getName());
            params.put("address",     address);
            params.put("description", "created via KB");

            logger.info("Creating customer in Stripe to be able to re-use the payment method");
            final Customer customer = Customer.create(params, requestOptions);

            logger.info("Mapping kbAccountId {} to Stripe customer {}", kbAccountId, customer.getId());
            try {
                killbillAPI.getCustomFieldUserApi().addCustomFields(
                        ImmutableList.of(new PluginCustomField(kbAccountId, ObjectType.ACCOUNT,
                                                            "STRIPE_CUSTOMER_ID", customer.getId(),
                                                            clock.getUTCNow())), context);
            } catch (final CustomFieldApiException e) {
                throw new PaymentPluginApiException("Unable to add custom field", e);
            }
            return customer;    // The customer id can be retrieved with customer.getId()
        } else {
            return null;        // Caller already has the ID if existingCustomerId != null.
                                // Callers that need a Customer object only call this when
                                // existingCustomerId is null (new-customer path).
        }
    }

    private String retrievePaymentMethod(final String customerId, final String existingCustomerId,
                                         final String defaultStripeId,
                                         final RequestOptions requestOptions) throws StripeException {
        if (existingCustomerId == null && customerId != null) {
            final String defaultSource = Customer.retrieve(customerId, expandSourcesParams, requestOptions).getDefaultSource();
            if (defaultSource != null) {
                return defaultSource;
            }
        }
        return defaultStripeId;
    }

    @Override
    protected String getPaymentMethodId(final StripePaymentMethodsRecord record) {
        return record.getKbPaymentMethodId();
    }

    // -------------------------------------------------------------------------
    // deletePaymentMethod
    // -------------------------------------------------------------------------

    @Override
    public void deletePaymentMethod(final UUID kbAccountId, final UUID kbPaymentMethodId,
                                    final Iterable<PluginProperty> properties,
                                    final CallContext context) throws PaymentPluginApiException {
        final StripePaymentMethodsRecord stripePaymentMethodsRecord;
        try {
            stripePaymentMethodsRecord = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve payment method", e);
        }

        // Bound check, valid result
        if (stripePaymentMethodsRecord == null) {
            throw new PaymentPluginApiException(
                "The payment method record is null.",
                new IllegalStateException("Missing STRIPE_CUSTOMER_ID")
            );
        }

        // Skip Stripe API calls for single-use methods. This can be done locally.
        if (StripeMethodExtensions.isSingleUseStripeId(stripePaymentMethodsRecord.getStripeId())) {
            logger.info("Deleting single-use payment method {} - no Stripe API call needed", kbPaymentMethodId);
            super.deletePaymentMethod(kbAccountId, kbPaymentMethodId, properties, context);
            return;
        }

        final RequestOptions requestOptions = buildRequestOptions(context);
        final String stripeId = stripePaymentMethodsRecord.getStripeId();
        
        try {
            if (stripeId.startsWith("pm_")) {
                // Modern PaymentMethod - detach from customer
                PaymentMethod.retrieve(stripeId, requestOptions).detach(requestOptions);
            } else if (stripeId.startsWith("src_")) {
                // Legacy Source - detach from customer (no params needed)
                Source.retrieve(stripeId, requestOptions).detach();
            } else if (stripeId.startsWith("card_") || stripeId.startsWith("ba_")) {
                // Legacy Card or BankAccount - delete from customer's sources
                String customerId = getCustomerIdNoException(kbAccountId, context);

                // Fallback: try to get customer_id from the stored payment method data
                // This is actually possible if the STRIPE_CUSTOMER_ID field is deleted for
                // some reason. But the deletion can continue, if the customer id has been
                // added to the payment method data, which it often is.

                if (customerId == null) {
                    logger.warn("STRIPE_CUSTOMER_ID custom field is missing for account {}", kbAccountId);
                    final Map<String, Object> additionalData = StripeDao.fromAdditionalData(
                            stripePaymentMethodsRecord.getAdditionalData());
                    customerId = (String) additionalData.get("customer_id");
                    
                    if (customerId != null) {
                        logger.info("Recovered customer_id={} from payment method additional_data for {}", 
                                customerId, stripeId);
                    } else {
                        throw new PaymentPluginApiException(
                            "Unable to find Stripe customer for legacy payment method " + stripeId 
                            + " on account " + kbAccountId,
                            new IllegalStateException("Missing STRIPE_CUSTOMER_ID")
                        );
                    }
                }

                final PaymentSource source = Customer.retrieve(customerId, expandSourcesParams, requestOptions)
                    .getSources()
                    .retrieve(stripeId, requestOptions);

                // Cast to the appropriate type and delete
                if (source instanceof Card) {
                    ((Card) source).delete(requestOptions);
                } else if (source instanceof BankAccount) {
                    ((BankAccount) source).delete(requestOptions);
                } else {
                    throw new PaymentPluginApiException("Unsupported payment source type", new UnsupportedOperationException());
                }

            } else {
                throw new PaymentPluginApiException("Unknown payment method type: " + stripeId, new UnsupportedOperationException());
            }
        } catch (final StripeException e) {
            throw new PaymentPluginApiException("Unable to delete Stripe payment method", e);
        }

        super.deletePaymentMethod(kbAccountId, kbPaymentMethodId, properties, context);
    }

    // -------------------------------------------------------------------------
    // getPaymentMethods — with refresh
    // -------------------------------------------------------------------------

    @Override
    public List<PaymentMethodInfoPlugin> getPaymentMethods(final UUID kbAccountId, final boolean refreshFromGateway,
                                                           final Iterable<PluginProperty> properties,
                                                           final CallContext context) throws PaymentPluginApiException {
        if (!refreshFromGateway) {
            return super.getPaymentMethods(kbAccountId, refreshFromGateway, properties, context);
        }

        // Build the map of known payment methods, EXCLUDING single-use records.
        // Single-use methods have sentinel stripe_ids that don't exist in Stripe,
        // so they must never enter the sync loop — they would be deleted as "not
        // found in Stripe" on every refresh otherwise.
        final Map<String, StripePaymentMethodsRecord> existingPaymentMethodByStripeId = new HashMap<>();
        try {
            for (final StripePaymentMethodsRecord record : dao.getPaymentMethods(kbAccountId, context.getTenantId())) {
                if (StripeMethodExtensions.isSingleUseStripeId(record.getStripeId())) {
                    continue; // Managed locally; never sync'd against Stripe
                }
                existingPaymentMethodByStripeId.put(record.getStripeId(), record);
            }
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve existing payment methods", e);
        }

        final String stripeCustomerId = getCustomerId(kbAccountId, context);
        final RequestOptions requestOptions = buildRequestOptions(context);
        final Set<String> stripeObjectsTreated = new HashSet<>();

        try {
            final Map<String, Object> paymentMethodParams = new HashMap<>();
            paymentMethodParams.put("customer", stripeCustomerId);

            paymentMethodParams.put("type", "card");
            syncPaymentMethods(kbAccountId,
                    PaymentMethod.list(paymentMethodParams, requestOptions).autoPagingIterable(paymentMethodParams, requestOptions),
                    existingPaymentMethodByStripeId, stripeObjectsTreated, context);

            paymentMethodParams.put("type", "sepa_debit");
            syncPaymentMethods(kbAccountId,
                    PaymentMethod.list(paymentMethodParams, requestOptions).autoPagingIterable(paymentMethodParams, requestOptions),
                    existingPaymentMethodByStripeId, stripeObjectsTreated, context);

            final PaymentSourceCollection psc = Customer.retrieve(stripeCustomerId, expandSourcesParams, requestOptions).getSources();
            if (psc != null) {
                syncPaymentMethods(kbAccountId,
                        psc.autoPagingIterable(paymentMethodParams, requestOptions),
                        existingPaymentMethodByStripeId, stripeObjectsTreated, context);
            }
        } catch (final StripeException e) {
            throw new PaymentPluginApiException("Error connecting to Stripe", e);
        } catch (final PaymentApiException e) {
            throw new PaymentPluginApiException("Error creating payment method", e);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Error creating payment method", e);
        }

        // Whatever remains in the map was not found in Stripe — deactivate locally.
        // Single-use records were excluded from the map above, so they are safe here.
        for (final StripePaymentMethodsRecord stripePaymentMethodsRecord : existingPaymentMethodByStripeId.values()) {
            logger.info("Deactivating local Stripe payment method {} - not found in Stripe", stripePaymentMethodsRecord.getStripeId());
            super.deletePaymentMethod(kbAccountId, UUID.fromString(stripePaymentMethodsRecord.getKbPaymentMethodId()), properties, context);
        }

        return super.getPaymentMethods(kbAccountId, false, properties, context);
    }

    private void syncPaymentMethods(final UUID kbAccountId,
                                final Iterable<? extends HasId> stripeObjects,
                                final Map<String, StripePaymentMethodsRecord> existingPaymentMethodByStripeId,
                                final Set<String> stripeObjectsTreated,
                                final CallContext context) throws PaymentApiException, SQLException
    {
        for (final HasId stripeObject : stripeObjects) {
            if (stripeObjectsTreated.contains(stripeObject.getId())) {
                continue;
            }
            stripeObjectsTreated.add(stripeObject.getId());

            final Map<String, Object> additionalDataMap;
            final String stripeId = stripeObject.getId();
            
            // Handle modern PaymentMethod objects (pm_*)
            if (stripeObject instanceof PaymentMethod) {
                additionalDataMap = StripePluginProperties.toAdditionalDataMap((PaymentMethod) stripeObject);
            } 
            // Handle legacy Card objects (card_*)
            else if (stripeObject instanceof Card) {
                additionalDataMap = StripePluginProperties.toAdditionalDataMap((Card) stripeObject);
            }
            // Handle legacy Source objects (src_*)
            else if (stripeObject instanceof Source) {
                additionalDataMap = StripePluginProperties.toAdditionalDataMap((Source) stripeObject);
            }
            // Fallback for other PaymentSource types
            else if (stripeObject instanceof PaymentSource) {
                additionalDataMap = StripePluginProperties.toAdditionalDataMap((PaymentSource) stripeObject);
            } 
            else {
                throw new UnsupportedOperationException("Unsupported object: " + stripeObject.getClass().getName() + " with ID: " + stripeId);
            }

            final StripePaymentMethodsRecord existingRecord = existingPaymentMethodByStripeId.remove(stripeId);
            if (existingRecord == null) {
                logger.info("Creating new local Stripe payment method {} (type: {})", stripeId, stripeObject.getClass().getSimpleName());
                final StripePaymentMethodPlugin paymentMethodInfo = new StripePaymentMethodPlugin(
                        null, stripeId, false, PluginProperties.buildPluginProperties(additionalDataMap));
                killbillAPI.getPaymentApi().addPaymentMethod(
                        getAccount(kbAccountId, context), stripeId,
                        StripeActivator.PLUGIN_NAME, false, paymentMethodInfo,
                        ImmutableList.of(), context);
            } else {
                logger.info("Updating existing local Stripe payment method {} (type: {})", stripeId, stripeObject.getClass().getSimpleName());
                dao.updatePaymentMethod(UUID.fromString(existingRecord.getKbPaymentMethodId()),
                                        additionalDataMap, stripeId,
                                        clock.getUTCNow(), context.getTenantId());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Payment transactions
    // -------------------------------------------------------------------------

    @Override
    public PaymentTransactionInfoPlugin authorizePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                         final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                         final BigDecimal amount, final Currency currency,
                                                         final Iterable<PluginProperty> properties,
                                                         final CallContext context) throws PaymentPluginApiException {
        final StripeResponsesRecord stripeResponsesRecord;
        try {
            stripeResponsesRecord = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("SQL exception when fetching response", e);
        }

        final boolean isHPPCompletion = stripeResponsesRecord != null
                && Boolean.parseBoolean(MoreObjects.firstNonNull(
                        StripeDao.fromAdditionalData(stripeResponsesRecord.getAdditionalData()).get(PROPERTY_FROM_HPP),
                        false).toString());

        if (!isHPPCompletion) {
            updateResponseWithAdditionalProperties(kbTransactionId, properties, context.getTenantId());
            return executeInitialTransaction(TransactionType.AUTHORIZE, kbAccountId, kbPaymentId,
                                             kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
        } else {
            updateResponseWithAdditionalProperties(kbTransactionId,
                    PluginProperties.merge(ImmutableMap.of(PROPERTY_HPP_COMPLETION, true), properties),
                    context.getTenantId());
        }
        return buildPaymentTransactionInfoPlugin(stripeResponsesRecord);
    }

    private void updateResponseWithAdditionalProperties(final UUID kbTransactionId,
                                                        final Iterable<PluginProperty> properties,
                                                        final UUID tenantId) throws PaymentPluginApiException {
        try {
            dao.updateResponse(kbTransactionId, properties, tenantId);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("SQL exception when updating response", e);
        }
    }

    @Override
    public PaymentTransactionInfoPlugin capturePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                       final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                       final BigDecimal amount, final Currency currency,
                                                       final Iterable<PluginProperty> properties,
                                                       final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.CAPTURE,
                new TransactionExecutor<PaymentIntent>() {
                    @Override
                    public PaymentIntent execute(final Account account,
                                                 final StripePaymentMethodsRecord paymentMethodsRecord,
                                                 final StripeResponsesRecord previousResponse) throws StripeException {
                        final RequestOptions requestOptions = buildRequestOptions(context);
                        final PaymentIntent intent = PaymentIntent.retrieve(
                                (String) StripeDao.fromAdditionalData(previousResponse.getAdditionalData()).get("id"),
                                requestOptions);
                        return intent.capture(ImmutableMap.of("amount_to_capture",
                                KillBillMoney.toMinorUnits(currency.toString(), amount)), requestOptions);
                    }
                },
                kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin purchasePayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                        final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                        final BigDecimal amount, final Currency currency,
                                                        final Iterable<PluginProperty> properties,
                                                        final CallContext context) throws PaymentPluginApiException {
        final StripeResponsesRecord stripeResponsesRecord;
        try {
            stripeResponsesRecord = dao.updateResponse(kbTransactionId, properties, context.getTenantId());
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("HPP notification came through, but we encountered a database error", e);
        }

        if (stripeResponsesRecord == null) {
            return executeInitialTransaction(TransactionType.PURCHASE, kbAccountId, kbPaymentId,
                                             kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
        }
        return buildPaymentTransactionInfoPlugin(stripeResponsesRecord);
    }

    @Override
    public PaymentTransactionInfoPlugin voidPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                    final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                    final Iterable<PluginProperty> properties,
                                                    final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.VOID,
                new TransactionExecutor<PaymentIntent>() {
                    @Override
                    public PaymentIntent execute(final Account account,
                                                 final StripePaymentMethodsRecord paymentMethodsRecord,
                                                 final StripeResponsesRecord previousResponse) throws StripeException {
                        final PaymentIntent intent = PaymentIntent.retrieve(
                                (String) StripeDao.fromAdditionalData(previousResponse.getAdditionalData()).get("id"),
                                buildRequestOptions(context));
                        return intent.cancel(buildRequestOptions(context));
                    }
                },
                kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, null, null, properties, context);
    }

    @Override
    public PaymentTransactionInfoPlugin creditPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                      final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                      final BigDecimal amount, final Currency currency,
                                                      final Iterable<PluginProperty> properties,
                                                      final CallContext context) throws PaymentPluginApiException {
        throw new PaymentPluginApiException("INTERNAL", "#creditPayment not yet implemented, please contact support@killbill.io");
    }

    @Override
    public PaymentTransactionInfoPlugin refundPayment(final UUID kbAccountId, final UUID kbPaymentId,
                                                      final UUID kbTransactionId, final UUID kbPaymentMethodId,
                                                      final BigDecimal amount, final Currency currency,
                                                      final Iterable<PluginProperty> properties,
                                                      final CallContext context) throws PaymentPluginApiException {
        return executeFollowUpTransaction(TransactionType.REFUND,
                new TransactionExecutor<PaymentIntent>() {
                    @Override
                    public PaymentIntent execute(final Account account,
                                                 final StripePaymentMethodsRecord paymentMethodsRecord,
                                                 final StripeResponsesRecord previousResponse) throws StripeException {
                        final RequestOptions requestOptions = buildRequestOptions(context);
                        final Map<String, Object> additionalData = StripeDao.fromAdditionalData(previousResponse.getAdditionalData());
                        final String paymentIntentId = (String) additionalData.get("id");
                        final String lastChargeId    = (String) additionalData.get("last_charge_id");
                        if (lastChargeId != null) {
                            final Map<String, Object> params = new HashMap<>();
                            params.put("charge", lastChargeId);
                            params.put("amount", KillBillMoney.toMinorUnits(currency.toString(), amount));
                            Refund.create(params, requestOptions);
                            return PaymentIntent.retrieve(paymentIntentId, requestOptions);
                        }
                        return null;
                    }
                },
                kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    @VisibleForTesting
    RequestOptions buildRequestOptions(final TenantContext context) {
        return stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId()).toRequestOptions();
    }

    @Override
    public HostedPaymentPageFormDescriptor buildFormDescriptor(final UUID kbAccountId,
                                                               final Iterable<PluginProperty> customFields,
                                                               final Iterable<PluginProperty> properties,
                                                               final CallContext context) throws PaymentPluginApiException {
        final RequestOptions requestOptions = buildRequestOptions(context);
        String stripeCustomerId = getCustomerIdNoException(kbAccountId, context);
        if (stripeCustomerId == null) {
            try {
                final Customer newCustomer = createStripeCustomer(
                    kbAccountId, null,
                    ImmutableMap.of(),
                    requestOptions,
                    properties,
                    context
                );
                if (newCustomer != null) {
                    stripeCustomerId = newCustomer.getId();
                }
            } catch (final StripeException e) {
                throw new PaymentPluginApiException("Unable to create Stripe customer", e);
            }
        }

        final Map<String, Object> params = new HashMap<>();
        final Map<String, Object> metadata = new HashMap<>();
        StreamSupport.stream(customFields.spliterator(), false)
                     .filter(entry -> !metadataFilter.contains(entry.getKey()))
                     .forEach(p -> metadata.put(p.getKey(), p.getValue()));
        params.put("metadata", metadata);
        params.put("customer", stripeCustomerId);

        final List<String> defaultPaymentMethodTypes = new ArrayList<>();
        defaultPaymentMethodTypes.add("card");
        final PluginProperty customPaymentMethods = StreamSupport.stream(customFields.spliterator(), false)
                .filter(entry -> "payment_method_types".equals(entry.getKey()))
                .findFirst().orElse(null);
        params.put("payment_method_types", customPaymentMethods != null && customPaymentMethods.getValue() != null
                ? customPaymentMethods.getValue() : defaultPaymentMethodTypes);
        params.put("mode",        "setup");
        params.put("expand",      Arrays.asList("setup_intent", "payment_intent"));
        params.put("success_url", PluginProperties.getValue("success_url", "https://example.com/success?sessionId={CHECKOUT_SESSION_ID}", customFields));
        params.put("cancel_url",  PluginProperties.getValue("cancel_url",  "https://example.com/cancel", customFields));

        final StripeConfigProperties stripeConfigProperties = stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId());
        try {
            logger.info("Creating Stripe session");
            final Session session = Session.create(params, requestOptions);
            dao.addHppRequest(kbAccountId, null, null, session, clock.getUTCNow(), context.getTenantId());
            final Map<String, Object> additionalDataMap = StripePluginProperties.toAdditionalDataMap(session, stripeConfigProperties.getPublicKey());
            if (session.getSetupIntentObject()   != null) additionalDataMap.put("setup_intent_client_secret",   session.getSetupIntentObject().getClientSecret());
            if (session.getPaymentIntentObject() != null) additionalDataMap.put("payment_intent_client_secret", session.getPaymentIntentObject().getClientSecret());
            return new PluginHostedPaymentPageFormDescriptor(kbAccountId, null, PluginProperties.buildPluginProperties(additionalDataMap));
        } catch (final StripeException e) {
            throw new PaymentPluginApiException("Unable to create Stripe session", e);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to save Stripe session", e);
        }
    }

    @Override
    public GatewayNotification processNotification(final String notification,
                                                final Iterable<PluginProperty> properties,
                                                final CallContext context) throws PaymentPluginApiException {

        logger.info("Received Stripe webhook");

        // The notification parameter contains the raw JSON body of the webhook
        if (notification == null || notification.isBlank()) {
            logger.warn("Received empty webhook payload");
            return null;
        }

        final String sigHeader = properties != null 
            ? PluginProperties.findPluginPropertyValue("Stripe-Signature", properties) 
            : null;

        final StripeConfigProperties config = stripeConfigPropertiesConfigurationHandler
                .getConfigurable(context.getTenantId());

        final String webhookSecret = config.getWebhookSecret();   // ← you must add this to StripeConfigProperties

        // When no secret is configured → signature verification is turned OFF
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.error("=================================================================");
            logger.error("⚠️  CRITICAL: USING DEFAULT WEBHOOK SECRET (INSECURE!)");
            logger.error("    The property 'org.killbill.billing.plugin.stripe.webhookSecret'");
            logger.error("    is not configured. Using fallback default secret.");
            logger.error("    ADD THIS TO YOUR KILLBILL CONFIG IMMEDIATELY:");
            logger.error("    org.killbill.billing.plugin.stripe.webhookSecret=whsec_xxxxxxxxxxxxxxxxxxxxxxxx");
            logger.error("=================================================================");
        }

        Event event;

        try {
            //
            // SIGNATURE VERIFICATION
            //
            if (webhookSecret == null || webhookSecret.isBlank()) {
                // Dev mode: skip signature verification completely
                event = Event.GSON.fromJson(notification, Event.class);
                logger.info("Parsed Stripe webhook WITHOUT signature verification, continuing (dev mode)...");
            } else {
                // Production mode: enforce signature verification
                if (sigHeader == null) {
                    // NO SIGNATURE HEADER FOUND
                    logger.warn("STRIPE WEBHOOK IGNORED: Webhook received without Stripe-Signature header.");
                    return null;
                }
                // This verifies the signature and parses the event If the signature doesn't match
                // throws a SignatureVerificationException
                event = Webhook.constructEvent(notification, sigHeader, webhookSecret);
                logger.info("Verified Stripe webhook event: type={} id={}", event.getType(), event.getId());
            }
            //
            // PROCESS STATUS UPDATES
            //
            if ("payment_intent.succeeded".equals(event.getType()) ||
                "payment_intent.payment_failed".equals(event.getType())) {

                final PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                        .getObject().orElse(null);
                if (intent != null) {
                    try {
                        // Look up the KB transaction by Stripe PI ID and update it
                        final StripeResponsesRecord record = dao.getResponseByStripeId(intent.getId(), context.getTenantId());
                        if (record != null) {
                            final Map<String, Object> update = new HashMap<>();
                            // Merge the latest intent state into additional_data
                            update.put("status", intent.getStatus());
                            if ("succeeded".equals(intent.getStatus())) {
                                update.put(PROPERTY_OVERRIDDEN_TRANSACTION_STATUS, PaymentPluginStatus.PROCESSED.toString());
                            } else if ("canceled".equals(intent.getStatus())) {
                                update.put(PROPERTY_OVERRIDDEN_TRANSACTION_STATUS, PaymentPluginStatus.ERROR.toString());
                            }
                            dao.updateResponse(record, update);
                            logger.info("Webhook: updated response for PI {} → {}", intent.getId(), intent.getStatus());
                        } else {
                            logger.warn("Webhook: no response record found for PI {}", intent.getId());
                        }
                    } catch (final SQLException e) {
                        logger.error("Webhook: DB error updating response for PI {}", intent.getId(), e);
                    }
                    return new PluginGatewayNotification(event.getId());
                }
            }
        } catch (final SignatureVerificationException e) {
            logger.warn("STRIPE WEBHOOK IGNORED: Invalid Stripe webhook signature", e);
        } catch (final Exception e) {
            logger.error("STRIPE WEBHOOK ERROR: Failed to process Stripe webhook", e);
        }

        return null;
    }

    // -------------------------------------------------------------------------
    // Internal transaction execution
    // -------------------------------------------------------------------------

    private abstract static class TransactionExecutor<T> {
        public T execute(final Account account, final StripePaymentMethodsRecord paymentMethodsRecord) throws StripeException {
            throw new UnsupportedOperationException();
        }
        public T execute(final Account account, final StripePaymentMethodsRecord paymentMethodsRecord,
                         final StripeResponsesRecord previousResponse) throws StripeException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Outer overload: builds the TransactionExecutor for the standard / single-use
     * initial payment flow and delegates to the inner overload.
     */
    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final CallContext context) throws PaymentPluginApiException {
        final String customerId = getCustomerIdNoException(kbAccountId, context);

        return executeInitialTransaction(transactionType,
                new TransactionExecutor<PaymentIntent>() {
                    @Override
                    public PaymentIntent execute(final Account account,
                                                 final StripePaymentMethodsRecord paymentMethodsRecord) throws StripeException {

                        final RequestOptions requestOptions = buildRequestOptions(context);
                        final StripeConfigProperties stripeConfigProperties =
                                stripeConfigPropertiesConfigurationHandler.getConfigurable(context.getTenantId());

                        // ── Read stored payment method data and detect type ──────────────
                        // IMPORTANT: singleUseType MUST be determined before building any
                        // payment_method or payment_method_types params. These two paths are
                        // mutually exclusive and must not be mixed.
                        final Map<String, Object> pmAdditionalData =
                                StripeDao.fromAdditionalData(paymentMethodsRecord.getAdditionalData());
                        final String singleUseType = StripeMethodExtensions.getSingleUseType(pmAdditionalData);

                        // Check currency. These methods are for Japan market only
                        // Verify: Is this strictly true? Are there any other markets/currencies that have these methods too? If so,
                        // this check requires modification to check all currencies. Currency.JPY should be replaced by a mehtod
                        // call to verify valid currency.
                        if (StripeMethodExtensions.requiresSpecialHandling(singleUseType)
                            && StripeMethodExtensions.validateSpecialHandlingCurrency(singleUseType, currency)
                        ) {
                            throw new RuntimeException("Konbini and Bank Transfer strictly require JPY currency.");
                        }

                        // ── Base params shared by both paths ────────────────────────────
                        final CaptureMethod captureMethod = transactionType == TransactionType.AUTHORIZE
                                ? CaptureMethod.MANUAL : CaptureMethod.AUTOMATIC;

                        final Map<String, Object> paymentIntentParams = new HashMap<>();
                        paymentIntentParams.put("amount",            KillBillMoney.toMinorUnits(currency.toString(), amount));
                        paymentIntentParams.put("currency",          currency.toString());
                        paymentIntentParams.put("capture_method",    captureMethod.value);
                        paymentIntentParams.put("description",       stripeConfigProperties.getChargeDescription());
                        paymentIntentParams.put("statement_descriptor_suffix", stripeConfigProperties.getChargeStatementDescriptor());
                        paymentIntentParams.put("metadata",          ImmutableMap.of(
                                "kbAccountId",       kbAccountId,
                                "kbPaymentId",       kbPaymentId,
                                "kbTransactionId",   kbTransactionId,
                                "kbPaymentMethodId", kbPaymentMethodId));
                        if (customerId != null) {
                            paymentIntentParams.put("customer", customerId);
                        }

                        if (StripeMethodExtensions.requiresSpecialHandling(singleUseType)) {
                            // ── Single-use path (konbini / bank_transfer) ────────────────
                            // Do NOT set "payment_method" or "confirm=true" here. The intent
                            // must be created unconfirmed, then confirmed separately below
                            // so Stripe generates the next_action (voucher / bank account).
                            // "customer" is required for customer_balance bank transfers.
                            paymentIntentParams.put(
                                "payment_method_types",
                                StripeMethodExtensions.buildPaymentMethodTypes(singleUseType)
                            );
                            // CRITICAL: Pass the stored additional data so we can pull fullname/email
                            paymentIntentParams.put("payment_method_data",    
                                StripeMethodExtensions.buildPaymentMethodData(singleUseType, pmAdditionalData));
                            paymentIntentParams.put("payment_method_options", 
                                StripeMethodExtensions.buildPaymentMethodOptions(
                                    singleUseType, pmAdditionalData, stripeConfigProperties.getChargeDescription()));
                            paymentIntentParams.put("confirmation_method", "automatic");
                            paymentIntentParams.put("confirm",             false);

                        } else {
                            // ── Standard path (card / SEPA / token / source) ─────────────
                            paymentIntentParams.put("confirm",              true);
                            paymentIntentParams.put("confirmation_method",  "automatic");

                            final String returnUrl = PluginProperties.findPluginPropertyValue("return_url", properties);
                            if (returnUrl != null) {
                                paymentIntentParams.put("return_url", returnUrl);
                            }

                            // Set payment_method or payment_method_data from the stored record
                            if (paymentMethodsRecord.getStripeId() != null && paymentMethodsRecord.getStripeId().startsWith("tok")) {
                                // Token-based card — use inline payment_method_data
                                paymentIntentParams.put("payment_method_data", ImmutableMap.of(
                                        "type", "card",
                                        "card", ImmutableMap.of("token", paymentMethodsRecord.getStripeId())));
                            } else if (paymentMethodsRecord.getStripeId() != null) {
                                // Standard stored PaymentMethod or Source
                                final String objectType = MoreObjects.firstNonNull(
                                        (String) pmAdditionalData.get("object"), "payment_method");
                                if ("payment_method".equals(objectType)) {
                                    paymentIntentParams.put("payment_method", paymentMethodsRecord.getStripeId());
                                } else {
                                    paymentIntentParams.put("payment_method", paymentMethodsRecord.getStripeId());
                                }
                            }

                            // Build the allowed payment_method_types list
                            final ImmutableList.Builder<String> pmTypesBuilder = ImmutableList.builder();
                            pmTypesBuilder.add("card");
                            if (captureMethod == CaptureMethod.AUTOMATIC && currency == Currency.EUR) {
                                pmTypesBuilder.add("sepa_debit");
                            }
                            if (transactionType == TransactionType.PURCHASE && currency == Currency.USD) {
                                pmTypesBuilder.add("us_bank_account");
                            }
                            paymentIntentParams.put("payment_method_types", pmTypesBuilder.build());
                        }

                        // The idempotency key uses the killbill payment (invoice) id and this prevents
                        // creating a new intent for the same invoice within a 24hour window.
                        // Generate idempotency key from transaction ID for consistency
                        String idempotencyKey = "kb_inv_" + kbPaymentId;
                        RequestOptions requestOptionsWithIdempotency = RequestOptions.builder()
                            .setApiKey(requestOptions.getApiKey())
                            .setIdempotencyKey(idempotencyKey)
                            .build();
                        // ── Create the PaymentIntent ─────────────────────────────────────
                        logger.info("Creating Stripe PaymentIntent (type={})", singleUseType != null ? singleUseType : "card");
                        PaymentIntent intent = PaymentIntent.create(paymentIntentParams, requestOptionsWithIdempotency);

                        // ── Confirm single-use intents to generate next_action details ───
                        // For konbini: next_action.konbini_display_details.confirmation_number
                        // For bank_transfer: next_action.display_bank_transfer_instructions
                        // These details are what the customer needs to complete payment.
                        // The confirmed intent is stored by dao.addResponse() below.
                        if (StripeMethodExtensions.requiresSpecialHandling(singleUseType)) {
                            // Confirm uses a DIFFERENT key — same base, different suffix
                            // Stripe requires distinct keys per endpoint. This is to satisfy
                            // this requirement.
                            final RequestOptions confirmOptions = RequestOptions.builder()
                                .setApiKey(requestOptions.getApiKey())
                                .setIdempotencyKey(idempotencyKey + "-confirm")
                                .build();
                            intent = StripeMethodExtensions.confirmIntent(intent, singleUseType, pmAdditionalData, confirmOptions);
                            logger.info("Single-use PaymentIntent {} confirmed, status={}", intent.getId(), intent.getStatus());
                        }

                        return intent;
                    }
                },
                kbAccountId, kbPaymentId, kbTransactionId, kbPaymentMethodId, amount, currency, properties, context);
    }

    /**
     * Inner overload: executes the TransactionExecutor and persists the result.
     */
    private PaymentTransactionInfoPlugin executeInitialTransaction(final TransactionType transactionType,
                                                                   final TransactionExecutor<PaymentIntent> transactionExecutor,
                                                                   final UUID kbAccountId,
                                                                   final UUID kbPaymentId,
                                                                   final UUID kbTransactionId,
                                                                   final UUID kbPaymentMethodId,
                                                                   final BigDecimal amount,
                                                                   final Currency currency,
                                                                   final Iterable<PluginProperty> properties,
                                                                   final TenantContext context) throws PaymentPluginApiException {
        final Account account = getAccount(kbAccountId, context);
        final StripePaymentMethodsRecord nonNullPaymentMethodsRecord = getStripePaymentMethodsRecord(kbPaymentMethodId, context);
        final DateTime utcNow = clock.getUTCNow();
        final RequestOptions requestOptions = buildRequestOptions(context);

        PaymentIntent response = null;
        StripeException stripeException = null;

        if (shouldSkipStripe(properties)) {
            throw new UnsupportedOperationException("TODO");
        } else {
            try {
                response = transactionExecutor.execute(account, nonNullPaymentMethodsRecord);
            } catch (final CardException e) {
                try {
                    final Charge charge = Charge.retrieve(e.getCharge(), requestOptions);
                    response = PaymentIntent.retrieve(charge.getPaymentIntent(), requestOptions);
                } catch (final StripeException e2) {
                    logger.warn("Error connecting to Stripe", e2);
                    stripeException = e2;
                }
            } catch (final StripeException e) {
                logger.warn("Error connecting to Stripe", e);
                stripeException = e;
            }
        }

        try {
            final Charge lastCharge = getLastCharge(response, Collections.emptyMap(), requestOptions);
            final StripeResponsesRecord responsesRecord = dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId,
                    transactionType, amount, currency, response, lastCharge, stripeException, utcNow, context.getTenantId());

            // Extract next_action details (vouchers, bank accounts) so the frontend can display them
            final Map<String, Object> pmAdditionalData = StripeDao.fromAdditionalData(nonNullPaymentMethodsRecord.getAdditionalData());
            final String singleUseType = StripeMethodExtensions.getSingleUseType(pmAdditionalData);
            
            if (StripeMethodExtensions.requiresSpecialHandling(singleUseType)) {
                final Map<String, Object> nextActionDetails = StripeMethodExtensions.extractNextActionDetails(response, singleUseType);
                if (!nextActionDetails.isEmpty()) {
                    logger.info("Extracted next_action details for {}: {}", singleUseType, nextActionDetails);
                    // Update the response record with the flattened voucher/bank details
                    dao.updateResponse(responsesRecord, nextActionDetails);
                    // Re-fetch the updated record so the plugin returns the new properties
                    final StripeResponsesRecord updatedRecord = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
                    return StripePaymentTransactionInfoPlugin.build(updatedRecord);
                }
            }

            return StripePaymentTransactionInfoPlugin.build(responsesRecord);
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + response, e);
        }
    }

    private PaymentTransactionInfoPlugin executeFollowUpTransaction(final TransactionType transactionType,
                                                                    final TransactionExecutor<PaymentIntent> transactionExecutor,
                                                                    final UUID kbAccountId,
                                                                    final UUID kbPaymentId,
                                                                    final UUID kbTransactionId,
                                                                    final UUID kbPaymentMethodId,
                                                                    @Nullable final BigDecimal amount,
                                                                    @Nullable final Currency currency,
                                                                    final Iterable<PluginProperty> properties,
                                                                    final TenantContext context) throws PaymentPluginApiException {
        final Account account = getAccount(kbAccountId, context);
        final StripePaymentMethodsRecord nonNullPaymentMethodsRecord = getStripePaymentMethodsRecord(kbPaymentMethodId, context);

        final StripeResponsesRecord previousResponse;
        try {
            previousResponse = dao.getSuccessfulAuthorizationResponse(kbPaymentId, context.getTenantId());
            if (previousResponse == null) {
                throw new PaymentPluginApiException(null, "Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId);
            }
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Unable to retrieve previous payment response for kbTransactionId " + kbTransactionId, e);
        }

        final DateTime utcNow = clock.getUTCNow();
        PaymentIntent response = null;
        StripeException stripeException = null;

        if (shouldSkipStripe(properties)) {
            throw new UnsupportedOperationException("TODO");
        } else {
            try {
                response = transactionExecutor.execute(account, nonNullPaymentMethodsRecord, previousResponse);
            } catch (final StripeException e) {
                logger.warn("Error connecting to Stripe", e);
                stripeException = e;
            }
        }

        try {
            final Charge lastCharge = getLastCharge(response, Collections.emptyMap(), buildRequestOptions(context));
            if (lastCharge != null) {
                final StripeResponsesRecord responsesRecord = dao.addResponse(kbAccountId, kbPaymentId, kbTransactionId,
                        transactionType, amount, currency, response, lastCharge, stripeException, utcNow, context.getTenantId());
                return StripePaymentTransactionInfoPlugin.build(responsesRecord);
            }
            return null;
        } catch (final SQLException e) {
            throw new PaymentPluginApiException("Payment went through, but we encountered a database error. Payment details: " + response, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String getCustomerId(final UUID kbAccountId, final CallContext context) throws PaymentPluginApiException {
        final String stripeCustomerId = getCustomerIdNoException(kbAccountId, context);
        if (stripeCustomerId == null) {
            throw new PaymentPluginApiException("INTERNAL", "Missing STRIPE_CUSTOMER_ID custom field");
        }
        return stripeCustomerId;
    }

    private String getCustomerIdNoException(final UUID kbAccountId, final TenantContext context) {
        for (final CustomField customField : killbillAPI.getCustomFieldUserApi()
                                                        .getCustomFieldsForAccountType(kbAccountId, ObjectType.ACCOUNT, context)) {
            if ("STRIPE_CUSTOMER_ID".equals(customField.getFieldName())) {
                return customField.getFieldValue();
            }
        }
        return null;
    }

    private StripePaymentMethodsRecord getStripePaymentMethodsRecord(@Nullable final UUID kbPaymentMethodId,
                                                                      final TenantContext context) throws PaymentPluginApiException {
        if (kbPaymentMethodId != null) {
            try {
                final StripePaymentMethodsRecord record = dao.getPaymentMethod(kbPaymentMethodId, context.getTenantId());
                if (record != null) return record;
            } catch (final SQLException e) {
                throw new PaymentPluginApiException("Failed to retrieve payment method", e);
            }
        }
        return emptyRecord(kbPaymentMethodId);
    }

    private StripePaymentMethodsRecord emptyRecord(@Nullable final UUID kbPaymentMethodId) {
        final StripePaymentMethodsRecord record = new StripePaymentMethodsRecord();
        if (kbPaymentMethodId != null) {
            record.setKbPaymentMethodId(kbPaymentMethodId.toString());
        }
        return record;
    }

    private boolean shouldSkipStripe(final Iterable<PluginProperty> properties) {
        return "true".equals(PluginProperties.findPluginPropertyValue("skipGw",   properties))
            || "true".equals(PluginProperties.findPluginPropertyValue("skip_gw", properties));
    }

    private Charge getLastCharge(@Nullable final PaymentIntent stripePaymentIntent,
                                  final Map<String, Object> params,
                                  final RequestOptions requestOptions) {
        if (stripePaymentIntent == null || stripePaymentIntent.getLatestCharge() == null) {
            return null;
        }
        String latestChargeId = stripePaymentIntent.getLatestCharge();
        if (latestChargeId != null) {
            try {
                Charge lastCharge = Charge.retrieve(latestChargeId, requestOptions);
                return (lastCharge != null) ? lastCharge : null;
            } catch (StripeException e) {
                // Handle the error appropriately for your use case
                logger.error("Failed to retrieve charge: " + latestChargeId, e);
                return null;
            }
        }
        return null;
    }
}