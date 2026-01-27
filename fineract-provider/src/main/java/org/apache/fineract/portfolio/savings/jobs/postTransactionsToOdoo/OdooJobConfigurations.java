package org.apache.fineract.portfolio.savings.jobs.postTransactionsToOdoo;

import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.infrastructure.odoo.OdooApisPort;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class OdooJobConfigurations {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JournalEntryRepository journalEntryRepository;
    @Autowired
    private OdooApisPort odooApisPort;

    @Bean
    protected Step generatePostJournalEntriesToOdooScheduleStep() {
        return new StepBuilder(JobName.POST_TRANSACTIONS_TO_ODOO.name(), jobRepository).tasklet(postJournalEntriesToOdoo(), transactionManager)
                .build();
    }

    @Bean
    public Job postJournalEntriesToOdooDetailsJob() {
        return new JobBuilder(JobName.POST_TRANSACTIONS_TO_ODOO.name(), jobRepository).start(generatePostJournalEntriesToOdooScheduleStep())
                .incrementer(new RunIdIncrementer()).build();
    }

    @Bean
    public PostJournalsToOdooTasklet postJournalEntriesToOdoo() {
        return new PostJournalsToOdooTasklet(journalEntryRepository, odooApisPort);
    }

}