package org.apache.fineract.portfolio.savings.jobs.postTransactionstoOdoo;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.odoo.OdooApisPort;
import org.apache.fineract.infrastructure.odoo.invoker.ApiException;
import org.apache.fineract.infrastructure.odoo.model.LedgerAccount;
import org.apache.fineract.infrastructure.odoo.model.SuccessResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final GLAccountRepository glAccountRepository;

    private static final Logger log = LoggerFactory.getLogger(PostAccountsToOdooTasklet.class);

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        final Collection<GLAccount> glAccounts = glAccountRepository.AndOdooRefIdIsNotNull();

        for (final GLAccount glAccount : glAccounts) {
            try {
                LedgerAccount ledgerAccount = new LedgerAccount(
                        glAccount.getId().toString(),
                        glAccount.getName(),
                        glAccount.getGlCode()
                );
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
