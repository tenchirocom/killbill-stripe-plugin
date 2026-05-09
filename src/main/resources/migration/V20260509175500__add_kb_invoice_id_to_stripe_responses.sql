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
 * Virtual payments deduplication guard for delayed confirmation transactions: 
 * Tracks which Kill Bill invoice is associated with a Stripe PaymentIntent.
 */
ALTER TABLE stripe_responses ADD COLUMN kb_invoice_id char(36) DEFAULT NULL AFTER kb_payment_id;
CREATE INDEX stripe_responses_kb_invoice_id ON stripe_responses(kb_invoice_id);