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
package org.apache.fineract.portfolio.savings.jobs.postjournalstoodoo;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.odoo.OdooApisPort;
import org.apache.fineract.infrastructure.odoo.invoker.ApiException;
import org.apache.fineract.infrastructure.odoo.model.SuccessResponse;
import org.apache.fineract.infrastructure.odoo.model.TransactionEntry;
import org.apache.fineract.infrastructure.odoo.model.TransactionRequest;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

@Slf4j
@RequiredArgsConstructor
public class PostJournalsToOdooTasklet implements Tasklet {

    private final JournalEntryRepository glJournalEntryRepository;

    private final OdooApisPort odooApisPort;

    String DATE_FORMATTER = "dd/MM/yyyy";
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMATTER);

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        final Collection<JournalEntry> entries = glJournalEntryRepository.findUnReversedManualJournalEntriesWhereOdooEntryIsNull();
        List<JournalEntry> transactionBatch = new ArrayList<>();
        String currentTransactionId = null;

        for (JournalEntry entry : entries) {
            String txId = entry.getTransactionId();
            if (currentTransactionId == null || currentTransactionId.equals(txId)) {
                transactionBatch.add(entry);
                currentTransactionId = txId;
            } else {
                // Process previous transaction group
                processTransaction(transactionBatch);
                // Start new batch
                transactionBatch = new ArrayList<>();
                transactionBatch.add(entry);
                currentTransactionId = txId;
            }
        }

        // process last batch
        if (!transactionBatch.isEmpty()) {
            processTransaction(transactionBatch);
        }

        return RepeatStatus.FINISHED;
    }

    private void processTransaction(List<JournalEntry> transactionBatch) {

        Optional<JournalEntry> journalEntry = transactionBatch.stream().findFirst();
        journalEntry.ifPresent(transaction -> {
            log.info("Processing Transaction ID: {}", transaction.getTransactionId());
            List<TransactionEntry> debits = transactionBatch.stream().filter(JournalEntry::isDebitEntry)
                    .map(it -> new TransactionEntry(it.getGlAccount().getId().intValue(), it.getAmount())).toList();

            List<TransactionEntry> credits = transactionBatch.stream().filter(JournalEntry::isCreditEntry)
                    .map(it -> new TransactionEntry(it.getGlAccount().getId().intValue(), it.getAmount())).toList();

            try {
                TransactionRequest ledgerAccount = new TransactionRequest(transaction.getOffice().getName(),
                        formatter.format(transaction.getTransactionDate()), DateUtils.getAuditOffsetDateTime(),
                        transaction.getTransactionId(), transaction.getDescription(), transaction.getCurrencyCode(), DATE_FORMATTER,
                        credits, debits);

                SuccessResponse successResponse = odooApisPort.odooPostLedger1(ledgerAccount);
                updateJournalWithOdooId(transactionBatch, successResponse);
            } catch (final PlatformApiDataValidationException | ApiException e) {
                log.error(e.getMessage(), e);
            }

        });

    }

    private void updateJournalWithOdooId(List<JournalEntry> accountJournals, SuccessResponse successResponse) {
        accountJournals.stream().forEach(it -> it.addOdooRefId(successResponse.getData().getResponseId()));
        glJournalEntryRepository.saveAll(accountJournals);
    }

}
