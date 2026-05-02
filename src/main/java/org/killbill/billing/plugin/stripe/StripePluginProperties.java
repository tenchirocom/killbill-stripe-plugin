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
 */

package org.killbill.billing.plugin.stripe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;

import com.google.common.base.Throwables;
import com.stripe.exception.StripeException;
import com.stripe.model.BankAccount;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethod.Card;
import com.stripe.model.PaymentSource;
import com.stripe.model.SetupIntent;
import com.stripe.model.SetupIntent.PaymentMethodOptions;
import com.stripe.model.Source;
import com.stripe.model.Source.AchDebit;
import com.stripe.model.Token;
import com.stripe.model.checkout.Session;
import com.stripe.model.BalanceTransaction;

import static org.killbill.billing.plugin.stripe.StripePaymentPluginApi.PROPERTY_OVERRIDDEN_TRANSACTION_STATUS;

// Stripe .toJson() is definitively not GDPR-friendly...
public abstract class StripePluginProperties {

    // ============================================================================
    // Public API - Overloaded methods for different Stripe object types
    // ============================================================================

    public static Map<String, Object> toAdditionalDataMap(final PaymentMethod stripePaymentMethod) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        // Add common PaymentMethod fields
        additionalDataMap.put("created", stripePaymentMethod.getCreated());
        additionalDataMap.put("customer_id", stripePaymentMethod.getCustomer());
        additionalDataMap.put("id", stripePaymentMethod.getId());
        additionalDataMap.put("livemode", stripePaymentMethod.getLivemode());
        additionalDataMap.put("metadata", stripePaymentMethod.getMetadata());
        additionalDataMap.put("object", stripePaymentMethod.getObject());
        additionalDataMap.put("type", stripePaymentMethod.getType());

        // Add payment method type-specific fields
        if (stripePaymentMethod.getCard() != null) {
            addPaymentMethodCardDetails(additionalDataMap, stripePaymentMethod.getCard());
        }
        if (stripePaymentMethod.getSepaDebit() != null) {
            addPaymentMethodSepaDebitDetails(additionalDataMap, stripePaymentMethod.getSepaDebit());
        }
        if (stripePaymentMethod.getUsBankAccount() != null) {
            addUsBankAccountDetails(additionalDataMap, stripePaymentMethod.getUsBankAccount());
        }

        return additionalDataMap;
    }

    public static Map<String, Object> toAdditionalDataMap(final PaymentSource stripePaymentSource) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        if (stripePaymentSource instanceof com.stripe.model.Card) {
            addLegacyCardDetails(additionalDataMap, (com.stripe.model.Card) stripePaymentSource);
        } else if (stripePaymentSource instanceof Source) {
            addSourceDetails(additionalDataMap, (Source) stripePaymentSource);
        } else if (stripePaymentSource instanceof BankAccount) {
            addBankAccountDetails(additionalDataMap, (BankAccount) stripePaymentSource);
        } else {
            throw new UnsupportedOperationException("Not yet supported: " + stripePaymentSource);
        }

        return additionalDataMap;
    }

    public static Map<String, Object> toAdditionalDataMap(final Token token) {
        if (token.getCard() != null) {
            return toAdditionalDataMap(token.getCard());
        } else if (token.getBankAccount() != null) {
            return toAdditionalDataMap(token.getBankAccount());
        } else {
            throw new UnsupportedOperationException("Not yet supported: " + token);
        }
    }

    public static Map<String, Object> toAdditionalDataMap(final StripeException stripeException) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        if (stripeException.getStripeError() != null) {
            additionalDataMap.put("stripe_error_message", stripeException.getStripeError().getMessage());
            additionalDataMap.put("stripe_error_code", stripeException.getStripeError().getCode());
        }
        additionalDataMap.put("code", stripeException.getCode());
        additionalDataMap.put("request_id", stripeException.getRequestId());
        additionalDataMap.put("status_code", stripeException.getStatusCode());
        additionalDataMap.put("message", stripeException.getMessage());
        additionalDataMap.put(PROPERTY_OVERRIDDEN_TRANSACTION_STATUS, mapExceptionToCallResult(stripeException).toString());

        return additionalDataMap;
    }

    public static Map<String, Object> toAdditionalDataMap(final PaymentIntent stripePaymentIntent, @Nullable final Charge lastCharge) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        additionalDataMap.put("amount", stripePaymentIntent.getAmount());
        additionalDataMap.put("amount_capturable", stripePaymentIntent.getAmountCapturable());
        additionalDataMap.put("amount_received", stripePaymentIntent.getAmountReceived());
        additionalDataMap.put("application", stripePaymentIntent.getApplication());
        additionalDataMap.put("application_fee_amount", stripePaymentIntent.getApplicationFeeAmount());
        additionalDataMap.put("canceled_at", stripePaymentIntent.getCanceledAt());
        additionalDataMap.put("cancellation_reason", stripePaymentIntent.getCancellationReason());
        additionalDataMap.put("capture_method", stripePaymentIntent.getCaptureMethod());
        additionalDataMap.put("confirmation_method", stripePaymentIntent.getConfirmationMethod());
        additionalDataMap.put("created", stripePaymentIntent.getCreated());
        additionalDataMap.put("currency", stripePaymentIntent.getCurrency());
        additionalDataMap.put("customer_id", stripePaymentIntent.getCustomer());
        additionalDataMap.put("description", stripePaymentIntent.getDescription());
        additionalDataMap.put("id", stripePaymentIntent.getId());
        additionalDataMap.put("invoice_id", stripePaymentIntent.getInvoice());
        additionalDataMap.put("last_payment_error", stripePaymentIntent.getLastPaymentError());
        additionalDataMap.put("livemode", stripePaymentIntent.getLivemode());
        additionalDataMap.put("metadata", stripePaymentIntent.getMetadata());
        additionalDataMap.put("next_action", stripePaymentIntent.getNextAction());
        additionalDataMap.put("object", stripePaymentIntent.getObject());
        additionalDataMap.put("on_behalf_of", stripePaymentIntent.getOnBehalfOf());
        additionalDataMap.put("payment_method_id", stripePaymentIntent.getPaymentMethod());
        additionalDataMap.put("payment_method_types", stripePaymentIntent.getPaymentMethodTypes());
        additionalDataMap.put("review_id", stripePaymentIntent.getReview());
        additionalDataMap.put("statement_descriptor", stripePaymentIntent.getStatementDescriptor());
        additionalDataMap.put("status", stripePaymentIntent.getStatus());
        additionalDataMap.put("transfer_group", stripePaymentIntent.getTransferGroup());

        if (lastCharge != null) {
            additionalDataMap.put("last_charge_amount", lastCharge.getAmount());
            additionalDataMap.put("last_charge_authorization_code", lastCharge.getAuthorizationCode());
            additionalDataMap.put("last_charge_balance_transaction_id", lastCharge.getBalanceTransaction());
            additionalDataMap.put("last_charge_created", lastCharge.getCreated());
            additionalDataMap.put("last_charge_currency", lastCharge.getCurrency());
            additionalDataMap.put("last_charge_description", lastCharge.getDescription());
            additionalDataMap.put("last_charge_failure_code", lastCharge.getFailureCode());
            additionalDataMap.put("last_charge_failure_message", lastCharge.getFailureMessage());
            additionalDataMap.put("last_charge_id", lastCharge.getId());
            additionalDataMap.put("last_charge_metadata", lastCharge.getMetadata());
            additionalDataMap.put("last_charge_object", lastCharge.getObject());
            additionalDataMap.put("last_charge_outcome", lastCharge.getOutcome());
            additionalDataMap.put("last_charge_paid", lastCharge.getPaid());
            additionalDataMap.put("last_charge_payment_method_id", lastCharge.getPaymentMethod());
            if (lastCharge.getPaymentMethodDetails() != null) {
                additionalDataMap.put("last_charge_payment_method_type", lastCharge.getPaymentMethodDetails().getType());
            }
            additionalDataMap.put("last_charge_statement_descriptor", lastCharge.getStatementDescriptor());
            additionalDataMap.put("last_charge_status", lastCharge.getStatus());
            // Additional transaction details
            BalanceTransaction bt = lastCharge.getBalanceTransactionObject();
            if (bt != null) {
                additionalDataMap.put("last_charge_balance_transaction_id", bt.getId());
                additionalDataMap.put("last_charge_balance_transaction_net", bt.getNet());
                additionalDataMap.put("last_charge_balance_transaction_fee", bt.getFee());
                additionalDataMap.put("last_charge_balance_transaction_amount", bt.getAmount());
                additionalDataMap.put("last_charge_balance_transaction_currency", bt.getCurrency());
            }
        }

        return additionalDataMap;
    }

    public static Map<String, Object> toAdditionalDataMap(final SetupIntent stripeSetupIntent) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        additionalDataMap.put("application", stripeSetupIntent.getApplication());
        additionalDataMap.put("cancellation_reason", stripeSetupIntent.getCancellationReason());
        additionalDataMap.put("created", stripeSetupIntent.getCreated());
        additionalDataMap.put("customer_id", stripeSetupIntent.getCustomer());
        additionalDataMap.put("description", stripeSetupIntent.getDescription());
        additionalDataMap.put("id", stripeSetupIntent.getId());
        additionalDataMap.put("last_setup_error", stripeSetupIntent.getLastSetupError());
        additionalDataMap.put("latest_attempt", stripeSetupIntent.getLatestAttempt());
        additionalDataMap.put("livemode", stripeSetupIntent.getLivemode());
        additionalDataMap.put("mandate", stripeSetupIntent.getMandate());
        additionalDataMap.put("metadata", stripeSetupIntent.getMetadata());
        additionalDataMap.put("next_action", stripeSetupIntent.getNextAction());
        additionalDataMap.put("object", stripeSetupIntent.getObject());
        additionalDataMap.put("on_behalf_of", stripeSetupIntent.getOnBehalfOf());
        additionalDataMap.put("payment_method_id", stripeSetupIntent.getPaymentMethod());
        
        final PaymentMethodOptions paymentMethodOptions = stripeSetupIntent.getPaymentMethodOptions();
        if (paymentMethodOptions != null) {
            final SetupIntent.PaymentMethodOptions.Card card = paymentMethodOptions.getCard();
            if (card != null) {
                additionalDataMap.put("payment_method_options_card_request_three_d_secure", card.getRequestThreeDSecure());
            }
        }
        
        additionalDataMap.put("payment_method_types", stripeSetupIntent.getPaymentMethodTypes());
        additionalDataMap.put("single_use_mandate_id", stripeSetupIntent.getSingleUseMandate());
        additionalDataMap.put("status", stripeSetupIntent.getStatus());
        additionalDataMap.put("usage", stripeSetupIntent.getUsage());

        return additionalDataMap;
    }

    public static Map<String, Object> toAdditionalDataMap(final Session session, @Nullable final String pk) {
        final Map<String, Object> additionalDataMap = new HashMap<String, Object>();

        additionalDataMap.put("billing_address_collection", session.getBillingAddressCollection());
        additionalDataMap.put("cancel_url", session.getCancelUrl());
        additionalDataMap.put("client_reference_id", session.getClientReferenceId());
        additionalDataMap.put("customer_id", session.getCustomer());
        additionalDataMap.put("line_items", session.getLineItems());
        additionalDataMap.put("id", session.getId());
        additionalDataMap.put("livemode", session.getLivemode());
        additionalDataMap.put("locale", session.getLocale());
        additionalDataMap.put("object", session.getObject());
        additionalDataMap.put("payment_intent_id", session.getPaymentIntent());
        additionalDataMap.put("payment_method_types", session.getPaymentMethodTypes());
        additionalDataMap.put("setup_intent_id", session.getSetupIntent());
        additionalDataMap.put("subscription_id", session.getSubscription());
        additionalDataMap.put("success_url", session.getSuccessUrl());
        if (pk != null) {
            additionalDataMap.put("publishable_key", pk);
        }

        return additionalDataMap;
    }

    // ============================================================================
    // Private helper methods - Extract payment method details
    // ============================================================================

    /**
     * Adds card details from modern PaymentMethod.Card object
     */
    private static void addPaymentMethodCardDetails(Map<String, Object> map, PaymentMethod.Card card) {
        map.put("card_brand", card.getBrand());
        map.put("card_country", card.getCountry());
        map.put("card_description", card.getDescription());
        map.put("card_exp_month", card.getExpMonth());
        map.put("card_exp_year", card.getExpYear());
        map.put("card_fingerprint", card.getFingerprint());
        map.put("card_funding", card.getFunding());
        map.put("card_iin", card.getIin());
        map.put("card_issuer", card.getIssuer());
        map.put("card_last4", card.getLast4());

        if (card.getChecks() != null) {
            map.put("card_address_line1_check", card.getChecks().getAddressLine1Check());
            map.put("card_address_postal_code_check", card.getChecks().getAddressPostalCodeCheck());
            map.put("card_cvc_check", card.getChecks().getCvcCheck());
        }

        if (card.getThreeDSecureUsage() != null) {
            map.put("card_three_d_secure_usage_support", card.getThreeDSecureUsage().getSupported());
        }

        if (card.getWallet() != null) {
            map.put("card_wallet_type", card.getWallet().getType());
        }
    }

    /**
     * Adds card details from legacy Card object (card_*)
     */
    private static void addLegacyCardDetails(Map<String, Object> map, com.stripe.model.Card card) {
        map.put("card_brand", card.getBrand());
        map.put("card_address_line1_check", card.getAddressLine1Check());
        map.put("card_address_postal_code_check", card.getAddressZipCheck());
        map.put("card_cvc_check", card.getCvcCheck());
        map.put("card_country", card.getCountry());
        map.put("card_description", card.getName());
        map.put("card_exp_month", card.getExpMonth());
        map.put("card_exp_year", card.getExpYear());
        map.put("card_fingerprint", card.getFingerprint());
        map.put("card_funding", card.getFunding());
        map.put("card_last4", card.getLast4());
    }

    /**
     * Adds all details from a Source object (src_*)
     */
    private static void addSourceDetails(Map<String, Object> map, Source stripeSource) {
        // Add card details if present
        final Source.Card card = stripeSource.getCard();
        if (card != null) {
            map.put("card_brand", card.getBrand());
            map.put("card_address_line1_check", card.getAddressLine1Check());
            map.put("card_address_postal_code_check", card.getAddressZipCheck());
            map.put("card_cvc_check", card.getCvcCheck());
            map.put("card_country", card.getCountry());
            map.put("card_description", card.getName());
            map.put("card_exp_month", card.getExpMonth());
            map.put("card_exp_year", card.getExpYear());
            map.put("card_fingerprint", card.getFingerprint());
            map.put("card_funding", card.getFunding());
            map.put("card_last4", card.getLast4());
            map.put("card_three_d_secure_usage_support", card.getThreeDSecure());
        }

        // Add ACH debit details if present
        final AchDebit achDebit = stripeSource.getAchDebit();
        if (achDebit != null) {
            map.put("ach_debit_bank_name", achDebit.getBankName());
            map.put("ach_debit_country", achDebit.getCountry());
            map.put("ach_debit_fingerprint", achDebit.getFingerprint());
            map.put("ach_debit_last4", achDebit.getLast4());
            map.put("ach_debit_routing_number", achDebit.getRoutingNumber());
            map.put("ach_debit_type", achDebit.getType());
        }

        // Add SEPA debit details if present
        final Source.SepaDebit sepaDebit = stripeSource.getSepaDebit();
        if (sepaDebit != null) {
            map.put("sepa_debit_bank_code", sepaDebit.getBankCode());
            map.put("sepa_debit_branch_code", sepaDebit.getBranchCode());
            map.put("sepa_debit_country", sepaDebit.getCountry());
            map.put("sepa_debit_fingerprint", sepaDebit.getFingerprint());
            map.put("sepa_debit_last4", sepaDebit.getLast4());
            map.put("sepa_debit_mandate_reference", sepaDebit.getMandateReference());
            map.put("sepa_debit_mandate_url", sepaDebit.getMandateUrl());
        }

        // Add common Source fields
        map.put("created", stripeSource.getCreated());
        map.put("customer_id", stripeSource.getCustomer());
        map.put("id", stripeSource.getId());
        map.put("livemode", stripeSource.getLivemode());
        map.put("metadata", stripeSource.getMetadata());
        map.put("object", stripeSource.getObject());
        map.put("type", stripeSource.getType());
    }

    /**
     * Adds SEPA debit details from modern PaymentMethod.SepaDebit object
     */
    private static void addPaymentMethodSepaDebitDetails(Map<String, Object> map, PaymentMethod.SepaDebit sepaDebit) {
        map.put("sepa_debit_bank_code", sepaDebit.getBankCode());
        map.put("sepa_debit_branch_code", sepaDebit.getBranchCode());
        map.put("sepa_debit_country", sepaDebit.getCountry());
        map.put("sepa_debit_fingerprint", sepaDebit.getFingerprint());
        map.put("sepa_debit_last4", sepaDebit.getLast4());
    }

    /**
     * Adds US bank account details from modern PaymentMethod.UsBankAccount object
     */
    private static void addUsBankAccountDetails(Map<String, Object> map, PaymentMethod.UsBankAccount usBankAccount) {
        map.put("us_bank_account_holder_type", usBankAccount.getAccountHolderType());
        map.put("us_bank_account_type", usBankAccount.getAccountType());
        map.put("bank_name", usBankAccount.getBankName());
        map.put("fingerprint", usBankAccount.getFingerprint());
        map.put("last4", usBankAccount.getLast4());
        map.put("routing_number", usBankAccount.getRoutingNumber());
    }

    /**
     * Adds bank account details from legacy BankAccount object
     */
    private static void addBankAccountDetails(Map<String, Object> map, BankAccount stripeBankAccount) {
        map.put("account_holder_type", stripeBankAccount.getAccountHolderType());
        map.put("bank_name", stripeBankAccount.getBankName());
        map.put("country", stripeBankAccount.getCountry());
        map.put("currency", stripeBankAccount.getCurrency());
        map.put("fingerprint", stripeBankAccount.getFingerprint());
        map.put("last4", stripeBankAccount.getLast4());
        map.put("routing_number", stripeBankAccount.getRoutingNumber());
        map.put("status", stripeBankAccount.getStatus());
        map.put("customer_id", stripeBankAccount.getCustomer());
        map.put("id", stripeBankAccount.getId());
        map.put("metadata", stripeBankAccount.getMetadata());
        map.put("object", stripeBankAccount.getObject());
    }

    // ============================================================================
    // Exception mapping
    // ============================================================================

    /**
     * Educated guess approach to transform exceptions into error status codes.
     */
    private static PaymentPluginStatus mapExceptionToCallResult(final Exception e) {
        final Throwable rootCause = Throwables.getRootCause(e);
        final String errorMessage = rootCause.getMessage();
        
        if (rootCause instanceof ConnectException) {
            return PaymentPluginStatus.CANCELED;
        } else if (rootCause instanceof SocketTimeoutException) {
            if (errorMessage.contains("Read timed out")) {
                return PaymentPluginStatus.UNDEFINED;
            } else if (errorMessage.contains("Unexpected end of file from server")) {
                return PaymentPluginStatus.UNDEFINED;
            }
        } else if (rootCause instanceof SocketException) {
            if (errorMessage.contains("Unexpected end of file from server")) {
                return PaymentPluginStatus.UNDEFINED;
            }
        } else if (rootCause instanceof UnknownHostException) {
            return PaymentPluginStatus.CANCELED;
        } else if (rootCause instanceof IOException) {
            if (errorMessage.contains("Invalid Http response")) {
                return PaymentPluginStatus.UNDEFINED;
            } else if (errorMessage.contains("Bogus chunk size")) {
                return PaymentPluginStatus.UNDEFINED;
            }
        }

        return PaymentPluginStatus.UNDEFINED;
    }
}
