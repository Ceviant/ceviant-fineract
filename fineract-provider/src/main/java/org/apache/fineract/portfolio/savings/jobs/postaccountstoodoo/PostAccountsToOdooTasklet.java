/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.jobs.postaccountstoodoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.odoo.OdooApisPort;
import org.apache.fineract.infrastructure.odoo.invoker.ApiException;
import org.apache.fineract.infrastructure.odoo.model.LedgerAccount;
import org.apache.fineract.infrastructure.odoo.model.SuccessResponse;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.Collection;

@Slf4j
@RequiredArgsConstructor
public class PostAccountsToOdooTasklet implements Tasklet {

    private final GLAccountRepository glAccountRepository;
    private final OdooApisPort odooApisPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        final Collection<GLAccount> glAccounts = glAccountRepository.AndOdooRefIdIsNotNull();

        for (final GLAccount glAccount : glAccounts) {
            try {
                LedgerAccount ledgerAccount = new LedgerAccount(glAccount.getId().toString(), glAccount.getName(), glAccount.getGlCode());
                SuccessResponse successResponse = odooApisPort.odooPostGlAccounts1(ledgerAccount);
                updateLedgerAccountWithOdooId(glAccount, successResponse);
            } catch (final PlatformApiDataValidationException | ApiException e) {
                log.error(e.getMessage(), e);
            }

        }
        return RepeatStatus.FINISHED;
    }

    private void updateLedgerAccountWithOdooId(GLAccount glAccount, SuccessResponse successResponse) {
        glAccount.setOdooRefId(successResponse.getData().getResponseId());
        glAccountRepository.save(glAccount);
    }
}
