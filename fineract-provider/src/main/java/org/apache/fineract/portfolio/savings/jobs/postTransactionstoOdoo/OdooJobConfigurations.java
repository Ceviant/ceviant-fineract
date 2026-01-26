package org.apache.fineract.portfolio.savings.jobs.postTransactionstoOdoo;


import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
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
    private GLAccountRepository glAccountRepository;
    @Autowired
    private OdooApisPort odooApisPort;


    @Bean
    protected Step generatePostAccountsToOdooScheduleStep() {
        return new StepBuilder(JobName.POST_ACCOUNTS_TO_ODOO.name(), jobRepository).tasklet(postGLAccountsToOdoo(), transactionManager)
                .build();
    }

    @Bean
    public Job postAccountsToOdooDetailsJob() {
        return new JobBuilder(JobName.POST_ACCOUNTS_TO_ODOO.name(), jobRepository).start(generatePostAccountsToOdooScheduleStep())
                .incrementer(new RunIdIncrementer()).build();
    }


    @Bean
    public PostAccountsToOdooTasklet postGLAccountsToOdoo() {
        return new PostAccountsToOdooTasklet(glAccountRepository, odooApisPort);
    }

}
