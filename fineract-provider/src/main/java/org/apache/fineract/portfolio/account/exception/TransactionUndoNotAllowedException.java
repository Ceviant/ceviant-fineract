/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * A {@link RuntimeException} thrown when update not allowed.
 */
public class TransactionUndoNotAllowedException extends AbstractPlatformDomainRuleException {

    public TransactionUndoNotAllowedException(final Long savingsId, final Long transactionId) {
        super("error.msg.saving.account.trasaction.undo.notallowed", "Savings Account transaction undo not allowed with savings identifier "
                + savingsId + " and trasaction identifier " + transactionId, savingsId, transactionId);
    }

    public TransactionUndoNotAllowedException(final String reference) {
        super("error.msg.saving.account.trasaction.update.notallowed",
                "Savings Account transaction undo not allowed with reference " + reference);
    }

    public TransactionUndoNotAllowedException(final String message, final String reference) {
        super("error.msg.saving.account.trasaction.update.notallowed",
                "Savings Account transaction undo not allowed with reference " + reference);
    }

    public TransactionUndoNotAllowedException(final Long message, final String reference) {
        super("error.msg.saving.account.trasaction.update.notallowed",
                "Intertenant transfer with reference " + reference + " was not found");
    }

    public TransactionUndoNotAllowedException() {
        super("error.msg.saving.account.trasaction.update.notallowed", "You cannot do inter tenant transfer within the same tenant");
    }
}
